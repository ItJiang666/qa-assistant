package com.itjiang.qa.provider;

import com.itjiang.qa.service.RuntimeModeService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
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

    /** 按模式缓存已构建的实例：构建一次要建 HTTP 客户端，不能每次请求都建。 */
    private final Map<String, ChatModel> chatCache = new ConcurrentHashMap<>();
    private final Map<String, ChatModel> extractCache = new ConcurrentHashMap<>();
    private final Map<String, StreamingChatModel> streamingCache = new ConcurrentHashMap<>();

    private final ChatModel routingChat;
    private final ChatModel routingExtract;
    private final StreamingChatModel routingStreaming;

    /** 向量模型：启动即定，跟配置模式走，不随运行时切换而变。 */
    private final EmbeddingModel embeddingModel;

    public RuntimeModelRegistry(LocalModelProvider localProvider,
                                CloudModelProvider cloudProvider,
                                RuntimeModeService modeService,
                                CloudEndpointResolver cloudResolver) {
        this.localProvider = localProvider;
        this.cloudProvider = cloudProvider;
        this.modeService = modeService;
        this.cloudResolver = cloudResolver;

        this.routingChat = new RoutingChatModel(() -> chatFor(modeService.current()), "qa");
        this.routingExtract = new RoutingChatModel(
                () -> extractFor(modeService.current()), "extract");
        this.routingStreaming = new RoutingStreamingChatModel(
                () -> streamingFor(modeService.current()), "qa-stream");

        RuntimeModeService.Mode embeddingMode = modeService.configured();
        log.info("向量模型跟随配置模式: {}（换向量模型需要重启，见类注释）", embeddingMode.value());
        this.embeddingModel = delegate(embeddingMode).embeddingModel();
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
     * @param mode 模式
     * @return 对话模型
     */
    private ChatModel chatFor(RuntimeModeService.Mode mode) {
        return chatCache.computeIfAbsent(mode.value(), key -> delegate(mode).chatModel());
    }

    /**
     * 取（或懒建）指定模式的抽取模型。
     *
     * @param mode 模式
     * @return 抽取模型
     */
    private ChatModel extractFor(RuntimeModeService.Mode mode) {
        return extractCache.computeIfAbsent(mode.value(), key -> delegate(mode).extractChatModel());
    }

    /**
     * 取（或懒建）指定模式的流式模型。
     *
     * @param mode 模式
     * @return 流式模型
     */
    private StreamingChatModel streamingFor(RuntimeModeService.Mode mode) {
        return streamingCache.computeIfAbsent(mode.value(), key -> delegate(mode).streamingChatModel());
    }
}
