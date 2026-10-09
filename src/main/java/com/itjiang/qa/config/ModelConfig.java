package com.itjiang.qa.config;

import com.itjiang.qa.provider.CloudModelProvider;
import com.itjiang.qa.provider.LocalModelProvider;
import com.itjiang.qa.provider.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 双模式模型装配（开发文档 4.4.2）。
 *
 * <p><b>装配分两层，这是有意的：</b>
 * <ol>
 *   <li>先按 {@code qa.mode} 选中唯一一个 {@link ModelProvider}（本地或云端）</li>
 *   <li>再把它的三个模型暴露成<b>具名 Bean</b>（{@code qaChatModel} 等），
 *       供 {@code @AiService} 按名字引用</li>
 * </ol>
 * 中间这层 provider 不是多余：没有它，条件装配就要在三个 Bean 上各写一遍，
 * 而且"当前用的是哪个模式"这件事会散落在三处，系统状态接口就要靠猜。
 *
 * <p><b>为什么用 {@code @ConditionalOnProperty} 而不是 Spring profile？</b>
 * profile 已经被数据源（h2/mysql）占用了。两条正交的轴混在一个 profile 上，
 * 会变成 h2-local / mysql-cloud 这样的笛卡尔积，配置项爆炸（决策 15 备注）。
 *
 * @author Jiang
 */
@Configuration
public class ModelConfig {

    /**
     * 本地模式供应商。
     *
     * <p>{@code matchIfMissing = true}：没写 {@code qa.mode} 时默认本地 ——
     * 本地模式不依赖网络和密钥，是最安全的默认值。
     *
     * @param properties     应用配置
     * @param numCtxResolver 上下文窗口解析器
     * @return 本地模型供应商
     */
    @Bean
    @ConditionalOnProperty(name = "qa.mode", havingValue = "local", matchIfMissing = true)
    public ModelProvider localModelProvider(QaProperties properties, NumCtxResolver numCtxResolver) {
        return new LocalModelProvider(properties, numCtxResolver);
    }

    /**
     * 云端模式供应商（骨架，P4 实现）。
     *
     * @param properties 应用配置
     * @return 云端模型供应商
     */
    @Bean
    @ConditionalOnProperty(name = "qa.mode", havingValue = "cloud")
    public ModelProvider cloudModelProvider(QaProperties properties) {
        return new CloudModelProvider(properties);
    }

    /**
     * 对外暴露的对话模型 Bean。
     *
     * @param provider 当前生效的模型供应商
     * @return 对话模型
     */
    @Bean("qaChatModel")
    public ChatModel qaChatModel(ModelProvider provider) {
        return provider.chatModel();
    }

    /**
     * 对外暴露的流式对话模型 Bean（{@code /api/v1/chat/stream} 使用）。
     *
     * @param provider 当前生效的模型供应商
     * @return 流式对话模型
     */
    @Bean("qaStreamingChatModel")
    public StreamingChatModel qaStreamingChatModel(ModelProvider provider) {
        return provider.streamingChatModel();
    }

    /**
     * 对外暴露的向量模型 Bean（入库与检索使用）。
     *
     * @param provider 当前生效的模型供应商
     * @return 向量模型
     */
    @Bean("qaEmbeddingModel")
    public EmbeddingModel qaEmbeddingModel(ModelProvider provider) {
        return provider.embeddingModel();
    }
}
