package com.itjiang.qa.assistant;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * 问答助手 —— 能力层核心（开发文档 10.1）。
 *
 * <p>这是一个<b>接口</b>，实现由 LangChain4j 在启动时生成。检索、记忆、
 * Prompt 注入、流式回调这些横切逻辑都由框架接管，业务代码只声明"我要什么"。
 *
 * <p>注意这里的依赖全部是<b>按 Bean 名字</b>引用的（{@code qaChatModel} 等），
 * 名字与 {@code ModelConfig} 中定义的 Bean 一一对应。改名字必须两边一起改，
 * 编译器帮不上忙 —— 这也是为什么装配集中在 {@code ModelConfig} 一个文件里。
 *
 * <p><b>⚠️ {@code wiringMode = EXPLICIT} 是必须的，不能省。</b>
 * 默认的 {@code AUTOMATIC} 模式会自动发现容器里"唯一的"检索组件并挂上来。
 * 于是每次对话都会先走一次向量检索：调用 embedding 模型（qwen3-embedding:4b，
 * 加载时占 5.8 GiB 显存）→ 把对话模型挤出显存 → 对话模型重载。
 *
 * <p>本机实测代价：单次问答 <b>7.9 秒 → 0.3 秒</b>（Ollama 侧每请求发生一次
 * "model predicted to exceed available memory, evicting"）。这类问题不会报错，
 * 只会让一切变慢，因此必须靠显式装配从根上避免。
 *
 * <p>真正需要检索时（P3），我们会在 {@code RagConfig} 里显式构建
 * {@code ContentRetriever} 并在这里指名引用 —— 那也是 EXPLICIT 模式。
 *
 * @author Jiang
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "qaChatModel",
        streamingChatModel = "qaStreamingChatModel",
        chatMemoryProvider = "qaChatMemoryProvider"
)
public interface QaAssistant {

    /**
     * 系统提示词。
     *
     * <p><b>这份提示词直接决定回答质量，改动前请先想清楚。</b>
     * 五条铁律分别对应五类失败模式：
     * <ol>
     *   <li>用模型自身的知识回答 → 用户拿到一个"看起来对"但没出处的答案</li>
     *   <li>资料里没有就硬编 → 编造的内部规定会被当成真的执行</li>
     *   <li>编造数字与流程 → 这是知识库问答里危害最大的一类幻觉</li>
     *   <li>不给出处 → 用户无法核对，错误答案会被无声地信任</li>
     *   <li>语言与格式漂移 → 中英夹杂、结构混乱，难读</li>
     * </ol>
     *
     * <p>P3 阶段会在此之上注入检索到的参考资料（{@code {{contents}}}），
     * P3.5 阶段会加入长期记忆段落。本阶段（P1）还没有检索，
     * 因此模型会因"资料中没有"而如实回答 —— <b>这正是我们要观察的行为</b>。
     */
    String SYSTEM_MESSAGE = """
            你是一个知识库问答助手。你的回答必须严格基于「参考资料」。

            【铁律】
            1. 只使用参考资料中的信息回答，不得用你自己的知识补充
            2. 参考资料中没有的内容，明确回答"现有资料中未找到相关信息"
            3. 严禁编造任何规定、数字、流程
            4. 引用具体内容时注明来源文档名
            5. 用中文回答；步骤用序号，结论放前面
            """;

    /**
     * 单轮问答（同步）。
     *
     * <p>{@code @MemoryId} 让框架按会话隔离记忆：同一个 conversationId 的历史会被带进
     * 本次请求的上下文，不同会话互不干扰。记忆的持久化由 {@code JdbcChatMemoryStore} 负责。
     *
     * <p>P3 阶段补上 {@code contentRetriever}；P3.5 补上长期记忆变量。
     *
     * @param conversationId 会话 ID（记忆隔离的键）
     * @param question       用户问题
     * @return 模型回答
     */
    @SystemMessage(SYSTEM_MESSAGE)
    String chat(@MemoryId String conversationId, @UserMessage String question);

    /**
     * 单轮问答（流式，{@code /api/v1/chat/stream} 使用）。
     *
     * <p>返回 {@code TokenStream} 而非直接吐文本：调用方需要挂上"逐字回调"、
     * "完成回调"和"错误回调"三个钩子 —— 尤其是完成回调，
     * 它是在流式场景下把回答落库的唯一时机。
     *
     * @param conversationId 会话 ID
     * @param question       用户问题
     * @return token 流，需由调用方 {@code .start()} 触发
     */
    @SystemMessage(SYSTEM_MESSAGE)
    TokenStream chatStream(@MemoryId String conversationId, @UserMessage String question);
}
