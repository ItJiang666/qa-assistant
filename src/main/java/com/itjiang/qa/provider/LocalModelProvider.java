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
        log.info("装配本地对话模型: {} @ {} (numCtx={}, think={})",
                cfg.getChatModel(), cfg.getBaseUrl(), numCtx, cfg.isThink());

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
        // think(...) 的声明返回类型是基类 OllamaBaseChatModel.Builder，
        // 会打断链式调用的类型推断，因此必须单独一行调用，不能再挂回链上
        builder.think(cfg.isThink());
        return builder.build();
    }

    @Override
    public StreamingChatModel streamingChatModel() {
        QaProperties.Local cfg = properties.getLocal();
        int numCtx = numCtxResolver.resolve();
        log.info("装配本地流式模型: {} (numCtx={}, think={})", cfg.getChatModel(), numCtx, cfg.isThink());

        OllamaStreamingChatModel.OllamaStreamingChatModelBuilder builder = OllamaStreamingChatModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .modelName(cfg.getChatModel())
                .temperature(0.3)
                .numCtx(numCtx)
                .timeout(parseTimeout(cfg.getTimeout()));
        builder.think(cfg.isThink());
        return builder.build();
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
