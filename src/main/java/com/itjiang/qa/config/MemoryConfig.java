package com.itjiang.qa.config;

import com.itjiang.qa.memory.CharBasedTokenCountEstimator;
import com.itjiang.qa.memory.JdbcChatMemoryStore;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * L2 会话记忆装配（开发文档 4.6.6 / 4.6.7）。
 *
 * <p>这里只做一件事：为每个会话造一个"按 token 裁剪的窗口"。
 *
 * <p><b>为什么按 token 而不是按条数？</b>
 * 按条数裁（{@code MessageWindowChatMemory.maxMessages(20)}）看起来更简单，
 * 但一轮"长文档问答"可能就吃掉 2000 token —— 20 条会直接撑爆上下文窗口，
 * 而模型不会报错，它会<b>静默地少看资料然后照常编答案</b>。
 * 按 token 裁才是与上下文窗口同一量纲的控制方式。
 *
 * <p>窗口大小取 {@code qa.memory.window-max-tokens}（默认 3000），
 * 与 4.6.6 的预算分配一致：系统提示 ~400 + 检索片段 ~2000 + 历史 ~3000 + 问答 ~1000 ≈ 8k，
 * 而 {@code num_ctx} 是 16384，留足余量。
 *
 * @author Jiang
 */
@Configuration
public class MemoryConfig {

    private static final Logger log = LoggerFactory.getLogger(MemoryConfig.class);

    /**
     * 会话记忆提供者。
     *
     * <p>Bean 名字 {@code qaChatMemoryProvider} 被 {@code QaAssistant} 的
     * {@code @AiService(chatMemoryProvider = ...)} 按名引用，改名必须两边一起改。
     *
     * @param store      会话记忆存储（落 H2/MySQL，重启不丢）
     * @param properties 应用配置
     * @param estimator  token 估算器
     * @return 会话记忆提供者
     */
    @Bean("qaChatMemoryProvider")
    public ChatMemoryProvider qaChatMemoryProvider(JdbcChatMemoryStore store,
                                                   QaProperties properties,
                                                   CharBasedTokenCountEstimator estimator) {
        int maxTokens = properties.getMemory().getWindowMaxTokens();
        log.info("会话记忆窗口: maxTokens={}（按 token 裁剪，而非按消息条数）", maxTokens);

        return memoryId -> TokenWindowChatMemory.builder()
                .id(memoryId)
                .maxTokens(maxTokens, estimator)
                // ★ 显式指定存储：默认是纯内存的 InMemoryChatMemoryStore，
                //   那样服务一重启历史就没了，而用户会以为"它还记得"
                .chatMemoryStore(store)
                // 系统提示词始终保留在窗口头部，不被裁剪掉
                .alwaysKeepSystemMessageFirst(true)
                .build();
    }
}
