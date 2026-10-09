package com.itjiang.qa.provider;

import com.itjiang.qa.config.NumCtxResolver;
import com.itjiang.qa.config.QaProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * 本地模型供应商：全部模型跑在本机 Ollama。
 *
 * <p>相比云端模式，这里多出两个必须显式设置的关键参数：
 * <ul>
 *   <li><b>{@code numCtx}</b> —— Ollama 的默认上下文窗口往往只有 4096，不改它，
 *       Java 侧算得再精确的 token 预算也会被服务端截断，而且<b>不会有任何报错</b>，
 *       表现为"模型明明有资料却说找不到"。这是最隐蔽的一类故障。</li>
 *   <li><b>{@code temperature=0.3}</b> —— 知识库问答要的是忠于原文，不是发挥创意。
 *       温度偏高会明显增加编造数字和流程的概率。</li>
 *   <li><b>{@code numPredict}</b> —— 单次回答的 token 上限（配置项
 *       {@code qa.local.max-output-tokens}）。本地生成速度约 60 token/s，
 *       不设上限时长篇回答会把单次请求拖到十几秒。</li>
 * </ul>
 *
 * @author Jiang
 */
public class LocalModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalModelProvider.class);

    private final QaProperties properties;
    private final NumCtxResolver numCtxResolver;

    public LocalModelProvider(QaProperties properties, NumCtxResolver numCtxResolver) {
        this.properties = properties;
        this.numCtxResolver = numCtxResolver;
    }

    @Override
    public ChatModel chatModel() {
        QaProperties.Local cfg = properties.getLocal();
        int numCtx = numCtxResolver.resolve();
        log.info("装配本地对话模型: {} @ {} (numCtx={}, think={}, maxOutputTokens={})",
                cfg.getChatModel(), cfg.getBaseUrl(), numCtx, cfg.isThink(), describeOutputLimit(cfg));

        OllamaChatModel.OllamaChatModelBuilder builder = OllamaChatModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .modelName(cfg.getChatModel())
                .temperature(0.3)
                .numCtx(numCtx)
                .timeout(parseTimeout(cfg.getTimeout()))
                // 本地调用失败多半是 Ollama 没启动，重试没有意义，快速失败更好定位
                .maxRetries(1)
                .logRequests(cfg.isLogRequests())
                .logResponses(cfg.isLogResponses());
        // think(...) 与 numPredict(...) 都继承自基类 OllamaBaseChatModel.Builder
        // （那个类是包级可见的，本包外写不出它的类型），返回类型会把链式调用的
        // 类型推断带偏，因此这两类设置一律单独一行调用，不能再挂回链上。
        builder.think(cfg.isThink());
        if (cfg.getMaxOutputTokens() > 0) {
            builder.numPredict(cfg.getMaxOutputTokens());
        }
        return builder.build();
    }

    @Override
    public ChatModel extractChatModel() {
        QaProperties.Local cfg = properties.getLocal();
        int numCtx = numCtxResolver.resolve();
        log.info("装配本地抽取模型: {} (temperature=0, think={}, maxOutputTokens={})",
                cfg.getChatModel(), cfg.isThink(), describeOutputLimit(cfg));

        OllamaChatModel.OllamaChatModelBuilder builder = OllamaChatModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .modelName(cfg.getChatModel())
                // 抽取要的是稳定性，不是创造力
                .temperature(0.0)
                .numCtx(numCtx)
                .timeout(parseTimeout(cfg.getTimeout()))
                .maxRetries(1);
        builder.think(cfg.isThink());
        if (cfg.getMaxOutputTokens() > 0) {
            builder.numPredict(cfg.getMaxOutputTokens());
        }
        return builder.build();
    }

    @Override
    public StreamingChatModel streamingChatModel() {
        QaProperties.Local cfg = properties.getLocal();
        int numCtx = numCtxResolver.resolve();
        log.info("装配本地流式模型: {} (numCtx={}, think={}, maxOutputTokens={})",
                cfg.getChatModel(), numCtx, cfg.isThink(), describeOutputLimit(cfg));

        OllamaStreamingChatModel.OllamaStreamingChatModelBuilder builder = OllamaStreamingChatModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .modelName(cfg.getChatModel())
                .temperature(0.3)
                .numCtx(numCtx)
                .timeout(parseTimeout(cfg.getTimeout()));
        builder.think(cfg.isThink());
        if (cfg.getMaxOutputTokens() > 0) {
            builder.numPredict(cfg.getMaxOutputTokens());
        }
        return builder.build();
    }

    /**
     * 生成日志里用的长度上限描述。
     *
     * @param cfg 本地模型配置
     * @return 形如 {@code 1024} 或 {@code 不限制}
     */
    private String describeOutputLimit(QaProperties.Local cfg) {
        return cfg.getMaxOutputTokens() > 0 ? String.valueOf(cfg.getMaxOutputTokens()) : "不限制";
    }

    @Override
    public EmbeddingModel embeddingModel() {
        QaProperties.Local cfg = properties.getLocal();
        log.info("装配本地向量模型: {} @ {}", cfg.getEmbeddingModel(), cfg.getBaseUrl());

        return OllamaEmbeddingModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .modelName(cfg.getEmbeddingModel())
                .timeout(parseTimeout(cfg.getTimeout()))
                .maxRetries(2)
                .build();
    }

    @Override
    public String name() {
        return "local";
    }

    /**
     * 解析超时配置。
     *
     * <p>支持两种写法：ISO-8601（{@code PT120S}）与简洁写法（{@code 120s} / {@code 5000ms}）。
     * 简洁写法对人更友好，这里手工解析；解析不了就回退到 120 秒，
     * 因为一个坏掉的超时值不该让整个应用起不来。
     *
     * @param raw 配置原文
     * @return 解析后的时长
     */
    private Duration parseTimeout(String raw) {
        Duration fallback = Duration.ofSeconds(120);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().toLowerCase();
        try {
            if (value.startsWith("pt")) {
                return Duration.parse(raw.trim().toUpperCase());
            }
            if (value.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2).trim()));
            }
            if (value.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1).trim()));
            }
            if (value.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1).trim()));
            }
            return Duration.ofSeconds(Long.parseLong(value));
        } catch (Exception ex) {
            log.warn("无法解析超时配置 '{}'，回退为 {} 秒", raw, fallback.toSeconds());
            return fallback;
        }
    }
}
