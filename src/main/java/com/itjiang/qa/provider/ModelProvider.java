package com.itjiang.qa.provider;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * 模型供应商抽象（开发文档 4.4.1）。
 *
 * <p>双模式（本地 Ollama / 云端 API）的全部差异都被收进这个接口：
 * 上层的 {@code QaAssistant}、检索、入库流水线只管拿模型用，<b>不关心模型从哪来</b>。
 * 于是"切换模式"这件事就退化成"换一个实现类"。
 *
 * <p>三个模型方法分开声明而不是合并成一个"模型包"对象，是因为它们的使用方完全不同：
 * 对话只依赖 {@link #chatModel()}，流式只依赖 {@link #streamingChatModel()}，
 * 入库流水线只依赖 {@link #embeddingModel()}。分开声明才能让依赖关系一目了然。
 *
 * @author Jiang
 */
public interface ModelProvider {

    /**
     * 同步对话模型。
     *
     * @return 对话模型
     */
    ChatModel chatModel();

    /**
     * 流式对话模型（SSE 输出用）。
     *
     * @return 流式对话模型
     */
    StreamingChatModel streamingChatModel();

    /**
     * 向量化模型。
     *
     * @return 向量模型
     */
    EmbeddingModel embeddingModel();

    /**
     * 供应商名称，用于日志与系统状态展示。
     *
     * @return {@code local} 或 {@code cloud}
     */
    String name();
}
