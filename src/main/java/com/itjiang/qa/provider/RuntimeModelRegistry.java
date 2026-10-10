package com.itjiang.qa.provider;

import com.itjiang.qa.service.RuntimeModeService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型注册表：管住"当前该用哪个模型"，并把可切换的那几个包成路由对象。
 *
 * <h2>本地与云端谁能运行时切、谁不能</h2>
 * <table border="1">
 *   <caption>切换能力对照</caption>
 *   <tr><th>模型</th><th>能否运行时切</th><th>原因</th></tr>
 *   <tr><td>对话 / 抽取 / 流式对话</td><td><b>能</b></td><td>换的是"谁来生成文字"，对已有数据没有影响</td></tr>
 *   <tr><td>向量（embedding）</td><td><b>不能，需重启</b></td><td>换向量模型 = 换向量空间。索引是在某个空间里建的，
 *       换过去之后旧向量既检索不准、又不会报错 —— 必须结合维度校验与重建索引，属于"重启时决定"的事</td></tr>
 * </table>
 * <p>所以本类里：对话类返回 {@link RoutingChatModel} / {@link RoutingStreamingChatModel}
 * （固定引用、内部按当前模式分发）；向量模型在启动时按 {@code qa.mode} 定下来，不再变。
 *
 * <h2>懒加载与"缺 Key 不许影响本地"</h2>
 * <p>云端模型<b>用到才建</b>：用户一直用本地，就不该因为没配 Key 而启动失败。
 * 反过来，{@code qa.mode=cloud} 时向量模型必须在启动时就建出来（检索器要用它加载索引），
 * 那时配置不全就该<b>立刻失败</b>，而不是等用户提第一个问题才报错。
 * 这两条看似矛盾，其实是一条规则：<b>只对"启动时就必须存在的东西"做启动期校验</b>。
 *
 * @author Jiang
 */
@Component
public class RuntimeModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(RuntimeModelRegistry.class);

    private final LocalModelProvider localProvider;
    private final CloudModelProvider cloudProvider;
    private final RuntimeModeService modeService;
    private final CloudEndpointResolver cloudResolver;

    /** 按「模式 + 云端配置快照」缓存已构建的实例：构建一次要建 HTTP 客户端，不能每次请求都建。 */
    private final Map<String, ChatModel> chatCache = new ConcurrentHashMap<>();
    private final Map<String, ChatModel> extractCache = new ConcurrentHashMap<>();
    private final Map<String, StreamingChatModel> streamingCache = new ConcurrentHashMap<>();

    private final ChatModel routingChat;
    private final ChatModel routingExtract;
    private final StreamingChatModel routingStreaming;

    /** 向量模型：启动即定，跟配置模式走，不随运行时切换而变。 */
    private final EmbeddingModel embeddingModel;

    /**
     * 装配向量模型时的云端配置快照。
     *
     * <p>留着它只有一个用途：<b>发现"改了向量模型却没重启"</b>。
     * 向量模型的来源在启动时定死（换它等于换向量空间），所以运行时改了配置
     * 绝不能悄悄生效 —— 但也不能装作没发生：用户会以为改成功了。
     * 于是启动时存一份，之后每次问状态都对比，不一致就明确告诉他"需重启"。
     */
    private final CloudEndpointResolver.CloudConfigView embeddingConfigAtStartup;

    public RuntimeModelRegistry(LocalModelProvider localProvider,
                                CloudModelProvider cloudProvider,
                                RuntimeModeService modeService,
                                CloudEndpointResolver cloudResolver,
                                ModelRequestLogger requestLogger) {
        this.localProvider = localProvider;
        this.cloudProvider = cloudProvider;
        this.modeService = modeService;
        this.cloudResolver = cloudResolver;

        this.routingChat = new RoutingChatModel(
                () -> chatFor(modeService.current()), "qa", requestLogger);
        this.routingExtract = new RoutingChatModel(
                () -> extractFor(modeService.current()), "extract", requestLogger);
        this.routingStreaming = new RoutingStreamingChatModel(
                () -> streamingFor(modeService.current()), "qa-stream", requestLogger);

        RuntimeModeService.Mode embeddingMode = modeService.configured();
        log.info("向量模型跟随配置模式: {}（换向量模型需要重启，见类注释）", embeddingMode.value());
        this.embeddingModel = delegate(embeddingMode).embeddingModel();
        this.embeddingConfigAtStartup = cloudResolver.configView();
    }

    /**
     * 可切换的对话模型（问答同步路径）。
     *
     * @return 路由模型，生命周期与应用一致
     */
    public ChatModel chatModel() {
        return routingChat;
    }

    /**
     * 可切换的抽取模型（L3 记忆抽取）。
     *
     * @return 路由模型
     */
    public ChatModel extractChatModel() {
        return routingExtract;
    }

    /**
     * 可切换的流式对话模型。
     *
     * @return 路由模型
     */
    public StreamingChatModel streamingChatModel() {
        return routingStreaming;
    }

    /**
     * 向量模型（启动时按 {@code qa.mode} 定下）。
     *
     * @return 向量模型
     */
    public EmbeddingModel embeddingModel() {
        return embeddingModel;
    }

    /**
     * 当前对话模式。
     *
     * @return 模式
     */
    public RuntimeModeService.Mode chatMode() {
        return modeService.current();
    }

    /**
     * 配置里的默认对话模式（重启后回到的那个值）。
     *
     * @return 模式
     */
    public RuntimeModeService.Mode configuredChatMode() {
        return modeService.configured();
    }

    /**
     * 当前向量模型所属的模式（与配置一致，不会变）。
     *
     * @return 模式
     */
    public RuntimeModeService.Mode embeddingMode() {
        return modeService.configured();
    }

    /**
     * 当前对话模型的可读名字（界面显示用）。
     *
     * @return 本地返回 Ollama 模型名；云端返回厂商模型名
     */
    public String chatModelName() {
        return chatMode() == RuntimeModeService.Mode.CLOUD
                ? cloudResolver.chat().model()
                : localProvider.chatModelName();
    }

    /**
     * 当前向量模型的可读名字。
     *
     * @return 模型名
     */
    public String embeddingModelName() {
        return embeddingMode() == RuntimeModeService.Mode.CLOUD
                ? cloudResolver.embedding().model()
                : localProvider.embeddingModelName();
    }

    /**
     * 当前云端厂商 id（界面显示"数据将发往哪里"）。
     *
     * @return 厂商 id；当前模式为本地时仍返回配置值，便于界面提示"切过去会发往哪里"
     */
    public String cloudProviderId() {
        return cloudResolver.chat().providerId();
    }

    /**
     * 丢弃已缓存的对话类模型，让下次请求按当前设置重新装配。
     *
     * <h2>什么时候必须调用</h2>
     * <p>凡是会改变"请求长什么样"的设置改了，就必须丢掉旧实例。目前只有一处
     * 需要显式调用：
     * <ol>
     *   <li><b>思考模式开关</b> —— 它改的是发给厂商的额外参数
     *       （如 DeepSeek 的 {@code thinking: {type: disabled}}），
     *       而参数是<b>装配期</b>固定在模型实例上的，不丢缓存就永远不生效。
     *       它<b>不在</b>缓存键里：开关是运行时状态，不是配置</li>
     *   <li>对话模式切换（本地 ↔ 云端）—— 缓存键里已含模式，本可不丢；
     *       仍然统一走这里，避免以后新增设置时漏掉一类缓存</li>
     * </ol>
     *
     * <p><b>改 Key / 改模型名不需要调用这个方法</b>：它们已经纳入缓存键
     * （见 {@link #cacheKey}），配置一变键就变、缓存自然未命中 ——
     * 这比"靠人记得清缓存"可靠得多。
     *
     * <p>为什么要成对丢掉抽取与流式模型：三者共用同一份厂商配置，
     * 只丢一半会出现"同步问答关了思考、流式问答还开着"这种自相矛盾的状态。
     *
     * <p>代价可控：下次请求重建实例只是新建一个 HTTP 客户端（毫秒级），
     * 不会重新加载任何权重 —— 云端模型是无状态的。
     */
    public void invalidateChatModels() {
        chatCache.clear();
        extractCache.clear();
        streamingCache.clear();
        log.info("已清除对话类模型缓存，下次请求将按当前设置重新装配");
    }

    /**
     * 按模式取供应商。
     *
     * @param mode 模式
     * @return 供应商实现
     */
    private ModelProvider delegate(RuntimeModeService.Mode mode) {
        return mode == RuntimeModeService.Mode.CLOUD ? cloudProvider : localProvider;
    }

    /**
     * 取（或懒建）指定模式的对话模型。
     *
     * <p>用 {@code computeIfAbsent} 而不是自己加锁：并发首次调用只会构建一次，
     * 构建抛异常时不会留下缓存条目 —— 于是"先失败、补好配置再重试"仍然可行。
     *
     * <p><b>缓存键里带上云端配置快照</b>：Key 与模型名是在装配那一刻固定进实例的，
     * 若只用模式做键，运行中换了 Key / 模型名之后，{@code /status} 会显示新值
     * 而实际请求仍用旧实例 —— 界面在撒谎。把快照纳入键之后，
     * 配置一变缓存自然未命中、自动重装实例，不需要重启，也不需要人工清缓存。
     *
     * <p>本地模式不拼快照：本地模型的配置（模型名、num_ctx）改了一样要重启才有意义，
     * 而且它不涉及凭据，纳入键只会让缓存无谓地膨胀。
     *
     * @param mode 模式
     * @return 对话模型
     */
    private ChatModel chatFor(RuntimeModeService.Mode mode) {
        return chatCache.computeIfAbsent(cacheKey(mode), key -> delegate(mode).chatModel());
    }

    /**
     * 取（或懒建）指定模式的抽取模型。
     *
     * @param mode 模式
     * @return 抽取模型
     */
    private ChatModel extractFor(RuntimeModeService.Mode mode) {
        return extractCache.computeIfAbsent(cacheKey(mode), key -> delegate(mode).extractChatModel());
    }

    /**
     * 取（或懒建）指定模式的流式模型。
     *
     * @param mode 模式
     * @return 流式模型
     */
    private StreamingChatModel streamingFor(RuntimeModeService.Mode mode) {
        return streamingCache.computeIfAbsent(cacheKey(mode), key -> delegate(mode).streamingChatModel());
    }

    /**
     * 组装模型缓存键。
     *
     * <p>云端模式下包含配置快照（厂商 / 端点 / 模型名 / Key 掩码），
     * 本地模式下只有模式名。快照里只有掩码、没有明文密钥，因此可以安全地
     * 出现在缓存键里（它不会被打印，但即便被打印也不泄露）。
     *
     * @param mode 模式
     * @return 缓存键
     */
    String cacheKey(RuntimeModeService.Mode mode) {
        if (mode != RuntimeModeService.Mode.CLOUD) {
            return mode.value();
        }
        CloudEndpointResolver.CloudConfigView view = cloudResolver.configView();
        return mode.value() + '|' + view.chatProviderId() + '|' + view.chatBaseUrl() + '|'
                + view.chatModel() + '|' + view.chatApiKeyMasked();
    }

    /**
     * 当前配置的向量模型是否与启动时装配的不一致。
     *
     * <p>不一致意味着"改了配置但没重启"：此时继续用旧向量模型是对的
     * （换向量空间必须连索引一起重建），但必须明确告知，不能装作没发生。
     *
     * @return 不一致返回 {@code true}
     */
    public boolean embeddingConfigChanged() {
        CloudEndpointResolver.CloudConfigView current = cloudResolver.configView();
        return !Objects.equals(embeddingConfigAtStartup.embeddingProviderId(), current.embeddingProviderId())
                || !Objects.equals(embeddingConfigAtStartup.embeddingModel(), current.embeddingModel())
                || !Objects.equals(embeddingConfigAtStartup.embeddingDimension(), current.embeddingDimension())
                || !Objects.equals(embeddingConfigAtStartup.embeddingBaseUrl(), current.embeddingBaseUrl())
                || !Objects.equals(embeddingConfigAtStartup.embeddingApiKeyMasked(), current.embeddingApiKeyMasked());
    }
}
