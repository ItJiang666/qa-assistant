package com.itjiang.qa.provider;

import com.itjiang.qa.config.QaProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * 云端模型供应商（骨架，P4 实现 —— 见开发文档决策 4）。
 *
 * <p><b>为什么现在就建这个类，而不是等到 P4？</b>
 * 因为 {@code ModelProvider} 抽象如果没有第二个实现，就无法证明这个抽象是对的 ——
 * 很容易写成"其实就是本地模式的包装"。先把第二个实现摆在这里，接口设计才算被验证过。
 *
 * <p><b>为什么直接抛异常，而不是"暂时委托给本地"？</b>
 * 用户把 {@code qa.mode} 设成 {@code cloud} 时，一定是想用云端。
 * 如果此时静默退回本地，他会以为云端配通了，直到某天发现文档其实没出网、
 * 或者反过来以为数据没出网而实际走的是云端 —— 两种误解都很危险。
 * <b>明确失败，好过悄悄做错事。</b>
 *
 * <p>P4 的落地要点（已在文档中定稿，此处留档避免遗忘）：
 * <ul>
 *   <li>对话：DeepSeek，{@code https://api.deepseek.com/v1}，OpenAI 兼容协议</li>
 *   <li>向量：硅基流动 {@code Qwen/Qwen3-Embedding-4B}（2560 维）——
 *       与本地 {@code qwen3-embedding:4b} 同源同维，因此两种模式切换<b>不需要重建索引</b></li>
 *   <li>需要新增依赖 {@code dev.langchain4j:langchain4j-open-ai}</li>
 *   <li>API Key 一律走环境变量，仓库里只留 {@code .env.example}</li>
 * </ul>
 *
 * @author Jiang
 */
public class CloudModelProvider implements ModelProvider {

    private final QaProperties properties;

    public CloudModelProvider(QaProperties properties) {
        this.properties = properties;
    }

    @Override
    public ChatModel chatModel() {
        throw notImplemented();
    }

    @Override
    public StreamingChatModel streamingChatModel() {
        throw notImplemented();
    }

    @Override
    public EmbeddingModel embeddingModel() {
        throw notImplemented();
    }

    @Override
    public String name() {
        return "cloud";
    }

    /**
     * 构造一条能指导用户下一步动作的异常。
     *
     * <p>错误信息里必须写清"当前是什么状态"和"你该怎么办"，
     * 而不是一句 "not implemented" 让人去翻源码。
     *
     * @return 异常实例
     */
    private IllegalStateException notImplemented() {
        return new IllegalStateException(
                "云端模式（qa.mode=cloud）尚未实现（计划在 P4 阶段交付）。"
                        + "当前请将 qa.mode 设为 local 使用本地 Ollama，"
                        + "或设置环境变量 QA_PROFILES=h2,local 回到默认配置。"
                        + "已配置的云端端点: chat=" + properties.getCloud().getChat().getBaseUrl()
                        + ", embedding=" + properties.getCloud().getEmbedding().getBaseUrl());
    }
}
