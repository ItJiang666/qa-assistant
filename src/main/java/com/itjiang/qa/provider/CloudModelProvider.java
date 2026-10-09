package com.itjiang.qa.provider;

import com.itjiang.qa.config.DurationParser;
import com.itjiang.qa.config.QaProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

/**
 * 云端模型供应商：用 OpenAI 兼容协议接国内主流厂商（P4）。
 *
 * <h2>为什么一套实现能接多家</h2>
 * <p>DeepSeek、阿里百炼（兼容模式）、硅基流动、智谱、月之暗面、火山方舟、腾讯混元、
 * MiniMax、阶跃星辰、百度千帆 v2 都对外提供 OpenAI 兼容端点，差异只有三样：
 * <b>base-url、模型名、Key</b>。这三样由 {@code qa.cloud.presets} 描述，
 * 所以加一家新厂商是<b>改配置</b>，不是改代码。
 *
 * <p>协议不兼容的两家（讯飞星火用自有 HTTP 协议 + 签名、文心旧版用 AK/SK 换 token）
 * 不在支持范围内。要接它们，就在这里加一个实现 {@code ModelProvider} 的类，
 * 并在 {@code ModelConfig} 里按 {@code qa.cloud.chat.provider} 选择 —— 扩展点就是这一层抽象。
 *
 * <h2>几个刻意的取舍</h2>
 * <ul>
 *   <li><b>不接 {@code logRequests} / {@code logResponses}</b>：LangChain4j 的请求日志会打印
 *       HTTP 头，里面就有 {@code Authorization: Bearer sk-...}。开启它等于把密钥写进日志文件。
 *       要排查请求体，宁可临时改代码并在事后轮换密钥。</li>
 *   <li><b>抽取模型单独建一个实例</b>（温度 0 + 可选 JSON 模式）：与本地模式同理，
 *       问答要的是通顺，抽取要的是格式稳定，两者不能共用一个实例。</li>
 *   <li><b>条件不齐时抛出带指引的异常</b>：延续"明确失败好过悄悄做错"的做法 ——
 *       缺 Key 就说缺哪个环境变量，而不是让它 401 之后再让人去猜。</li>
 * </ul>
 *
 * @author Jiang
 */
public class CloudModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(CloudModelProvider.class);

    /** 问答温度：与本地一致。知识库问答要的是忠于原文，不是发挥创意。 */
    private static final double QA_TEMPERATURE = 0.3;

    /** 抽取温度：越低越稳，0 是 OpenAI 协议允许的下限。 */
    private static final double EXTRACT_TEMPERATURE = 0.0;

    private final QaProperties properties;
    private final CloudEndpointResolver resolver;

    public CloudModelProvider(QaProperties properties, CloudEndpointResolver resolver) {
        this.properties = properties;
        this.resolver = resolver;
    }

    @Override
    public ChatModel chatModel() {
        CloudEndpointResolver.ResolvedEndpoint endpoint = resolver.chat();
        requireUsable(endpoint, "对话");
        Map<String, Object> extra = resolver.chatExtraParameters();
        log.info("装配云端对话模型: {} @ {} (provider={}, temperature={}, maxTokens={}, 额外参数={})",
                endpoint.model(), endpoint.baseUrl(), endpoint.providerId(),
                QA_TEMPERATURE, properties.getCloud().getChat().getMaxOutputTokens(),
                extra.isEmpty() ? "无" : extra.keySet());

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(endpoint.apiKey())
                .modelName(endpoint.model())
                .temperature(QA_TEMPERATURE)
                .maxTokens(maxTokensOrNull())
                .timeout(timeout(properties.getCloud().getChat().getTimeout()))
                .maxRetries(properties.getCloud().getChat().getMaxRetries());
        applyExtra(builder, extra);
        return builder.build();
    }

    @Override
    public ChatModel extractChatModel() {
        CloudEndpointResolver.ResolvedEndpoint endpoint = resolver.chat();
        requireUsable(endpoint, "抽取");
        boolean jsonMode = properties.getCloud().getChat().isJsonMode();
        log.info("装配云端抽取模型: {} (temperature=0, jsonMode={})", endpoint.model(), jsonMode);

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(endpoint.apiKey())
                .modelName(endpoint.model())
                .temperature(EXTRACT_TEMPERATURE)
                .maxTokens(maxTokensOrNull())
                .timeout(timeout(properties.getCloud().getChat().getTimeout()))
                .maxRetries(properties.getCloud().getChat().getMaxRetries());
        if (jsonMode) {
            // 让协议层保证"只输出 JSON"，比在提示词里写"请只输出 JSON"可靠得多。
            // 个别厂商不支持该参数会返回 400，那时把 qa.cloud.chat.json-mode 设为 false 即可。
            builder.responseFormat(ResponseFormat.JSON);
        }
        applyExtra(builder, resolver.chatExtraParameters());
        return builder.build();
    }

    @Override
    public StreamingChatModel streamingChatModel() {
        CloudEndpointResolver.ResolvedEndpoint endpoint = resolver.chat();
        requireUsable(endpoint, "流式对话");
        Map<String, Object> extra = resolver.chatExtraParameters();
        log.info("装配云端流式模型: {} @ {} (额外参数={})", endpoint.model(), endpoint.baseUrl(),
                extra.isEmpty() ? "无" : extra.keySet());

        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(endpoint.apiKey())
                .modelName(endpoint.model())
                .temperature(QA_TEMPERATURE)
                .maxTokens(maxTokensOrNull())
                .timeout(timeout(properties.getCloud().getChat().getTimeout()));
        applyExtra(builder, extra);
        return builder.build();
    }

    @Override
    public EmbeddingModel embeddingModel() {
        CloudEndpointResolver.ResolvedEndpoint endpoint = resolver.embedding();
        requireUsable(endpoint, "向量");
        QaProperties.Cloud.Embedding cfg = properties.getCloud().getEmbedding();
        int batchSize = resolver.embeddingBatchSize();
        log.info("装配云端向量模型: {} @ {} (provider={}, 维度={}, 批大小={}{})",
                endpoint.model(), endpoint.baseUrl(), endpoint.providerId(),
                endpoint.dimension() == null ? "未声明" : endpoint.dimension(), batchSize,
                batchSize == cfg.getBatchSize() ? "" : "（已按厂商上限从 " + cfg.getBatchSize() + " 下调）");

        OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder = OpenAiEmbeddingModel.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(endpoint.apiKey())
                .modelName(endpoint.model())
                .timeout(timeout(cfg.getTimeout()))
                .maxRetries(cfg.getMaxRetries())
                // 云端有限制：一份上百片段的文档一次性发过去会 400/429，必须分批
                .maxSegmentsPerBatch(batchSize);
        if (endpoint.dimension() != null) {
            // 部分模型（如百炼 text-embedding-v4）支持指定输出维度。
            // 对不支持该参数的模型，OpenAI 协议允许忽略，故只在显式声明时才带上。
            builder.dimensions(endpoint.dimension());
        }
        return builder.build();
    }

    @Override
    public String name() {
        return "cloud";
    }

    /**
     * 把厂商专属参数挂到同步模型上。
     *
     * <p>与下面那个重载是同一件事，只是两个构建器没有共同的父接口
     * （{@code OpenAiChatModelBuilder} 与 {@code OpenAiStreamingChatModelBuilder}
     * 各自独立），所以只能写两份。逻辑只有一行，不值得为它造抽象。
     *
     * @param builder 同步模型构建器
     * @param extra   额外参数
     */
    private void applyExtra(OpenAiChatModel.OpenAiChatModelBuilder builder, Map<String, Object> extra) {
        if (!extra.isEmpty()) {
            builder.customParameters(extra);
        }
    }

    /**
     * 把厂商专属参数挂到流式模型上。
     *
     * @param builder 流式模型构建器
     * @param extra   额外参数
     */
    private void applyExtra(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder,
                            Map<String, Object> extra) {
        if (!extra.isEmpty()) {
            builder.customParameters(extra);
        }
    }

    /**
     * 校验端点三要素齐备。
     *
     * @param endpoint 解析后的端点
     * @param purpose  用途描述，用于拼错误信息（"对话" / "向量" …）
     */
    private void requireUsable(CloudEndpointResolver.ResolvedEndpoint endpoint, String purpose) {
        if (!endpoint.usable()) {
            throw new IllegalStateException(
                    "云端" + purpose + "端点配置不完整，缺少：" + endpoint.missingHint()
                            + "。请在环境变量或 qa.cloud 配置中补齐后重试；"
                            + "只想用本地模型的话，把 qa.mode 设为 local（界面上的模型来源切回本地亦可）。");
        }
    }

    /**
     * 生成长度上限。
     *
     * @return 上限值；{@code <=0} 表示不限制，返回 {@code null} 以不发送该参数
     */
    private Integer maxTokensOrNull() {
        int limit = properties.getCloud().getChat().getMaxOutputTokens();
        return limit > 0 ? limit : null;
    }

    /**
     * 解析超时配置。
     *
     * @param raw 配置原文
     * @return 超时时长；无法解析时回退 60 秒
     */
    private Duration timeout(String raw) {
        return DurationParser.parse(raw, Duration.ofSeconds(60));
    }
}
