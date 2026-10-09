package com.itjiang.qa.assistant;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 问答助手 —— 能力层核心（开发文档 10.1）。
 *
 * <p>这是一个<b>接口</b>，实现由 LangChain4j 在启动时生成。检索、记忆与流式回调
 * 这些横切逻辑都由框架接管，业务代码只声明"我要什么"。
 *
 * <p>依赖全部<b>按 Bean 名字</b>引用（{@code qaChatModel} 等），名字与
 * {@code ModelConfig} / {@code RagConfig} / {@code MemoryConfig} 中定义的 Bean
 * 一一对应。改名字必须两边一起改，编译器帮不上忙 —— 这也是为什么装配集中在配置类里。
 *
 * <h2>⚠️ 这里为什么没有 {@code @SystemMessage}</h2>
 * <p>不是忘了，是<b>刻意移除的</b>。系统提示词改由
 * {@code JdbcChatMemoryStore.getMessages()} 拼在消息列表最前面。
 *
 * <p>原因：一旦配置了 {@code chatMemoryProvider}，LangChain4j 的 ChatMemory 就以
 * {@code chatMemoryStore} 为准 —— 框架把 {@code @SystemMessage} 构造的消息
 * {@code add} 进内存后会调用 {@code updateMessages()}，而那个方法是刻意留空的
 * （为了让对话历史只由 ChatService 唯一落库）；紧接着框架又从 {@code getMessages()}
 * 重新读取，于是 system 消息当场丢失。
 *
 * <p>实测现象：请求体里 {@code messages} 数组只有一条 user 消息，system 段完全不存在，
 * 铁律与长期记忆都没进 Prompt，而且<b>不报任何错</b>。这类"配置看起来对、实际没生效"
 * 的问题只能靠抓请求体发现，因此在这里留下完整记录。
 *
 * <h2>wiringMode 必须是 EXPLICIT</h2>
 * <p>默认的 AUTOMATIC 会自动发现容器里的检索组件并挂上，导致每次对话都先做一次
 * 向量检索、加载 embedding 模型、把对话模型挤出显存 —— 实测单次问答 7.9 秒。
 * 改显式后 0.15 秒，相差约 50 倍。
 *
 * @author Jiang
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "qaChatModel",
        streamingChatModel = "qaStreamingChatModel",
        chatMemoryProvider = "qaChatMemoryProvider",
        contentRetriever = "qaContentRetriever"
)
public interface QaAssistant {

    /**
     * 单轮问答（同步）。
     *
     * <p>{@code @MemoryId} 让框架按会话隔离 L2 记忆：同一 conversationId 的历史会被带进
     * 本次请求，由 {@code JdbcChatMemoryStore} 从数据库读取。
     *
     * <p>长期记忆（L3）由 {@code ChatService} 拼进 {@code question} 一起传进来 ——
     * 它在用户消息里，而不是系统消息里：一方面是系统消息由 store 供给、无法按请求插值，
     * 另一方面紧挨着问题的位置对模型的约束力更强（已实测验证）。
     *
     * @param conversationId 会话 ID（L2 记忆隔离的键）
     * @param question       用户消息（原始问题，必要时已附加长期记忆段落）
     * @return 模型回答
     */
    String chat(@MemoryId String conversationId, @UserMessage String question);

    /**
     * 单轮问答（流式，{@code /api/v1/chat/stream} 使用）。
     *
     * <p>返回 {@code TokenStream} 而非直接吐文本：调用方需要挂上逐字回调、
     * 检索结果回调、完成回调与错误回调 —— 尤其是完成回调，
     * 它是在流式场景下把回答落库、并触发长期记忆抽取的唯一时机。
     *
     * @param conversationId 会话 ID
     * @param question       用户消息
     * @return token 流，需由调用方 {@code .start()} 触发
     */
    TokenStream chatStream(@MemoryId String conversationId, @UserMessage String question);
}
