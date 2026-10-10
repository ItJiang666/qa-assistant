package com.itjiang.qa.config;

import com.itjiang.qa.provider.CloudEndpointResolver;
import com.itjiang.qa.provider.CloudModelProvider;
import com.itjiang.qa.provider.LocalModelProvider;
import com.itjiang.qa.provider.RuntimeModelRegistry;
import com.itjiang.qa.service.ThinkingModeService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 双模式模型装配（开发文档 4.4.2）。
 *
 * <p><b>装配分三层，P4 起多出来的第三层是必要的：</b>
 * <ol>
 *   <li>两个 {@code ModelProvider} <b>同时</b>注册（本地 + 云端）——
 *       因为对话模式可以在运行时切，两个都得在容器里</li>
 *   <li>{@link RuntimeModelRegistry} 管"当前该用谁"，并把可切换的三个模型
 *       （对话 / 抽取 / 流式）包成路由对象；向量模型则按 {@code qa.mode} 启动即定</li>
 *   <li>对外仍暴露同样的具名 Bean（{@code qaChatModel} 等），
 *       {@code @AiService} 的 EXPLICIT 装配<b>一个字都不用改</b></li>
 * </ol>
 *
 * <p><b>P4 之前这里为什么用 {@code @ConditionalOnProperty} 二选一：</b>
 * 那时模式只在启动时决定，条件装配足够。现在对话模式要运行时切，二选一就不成立了 ——
 * 但 profile 那条轴（数据源 h2/mysql）仍然由 profile 决定，没有被搅进来（决策 15 备注）。
 *
 * @author Jiang
 */
@Configuration
public class ModelConfig {

    /**
     * 本地模式供应商。
     *
     * <p>无条件注册：本地是本项目的默认与兜底，任何时候都该可用。
     *
     * @param properties     应用配置
     * @param numCtxResolver 上下文窗口解析器
     * @return 本地模型供应商
     */
    @Bean
    public LocalModelProvider localModelProvider(QaProperties properties, NumCtxResolver numCtxResolver) {
        return new LocalModelProvider(properties, numCtxResolver);
    }

    /**
     * 云端模式供应商。
     *
     * <p>同样无条件注册，但注意它<b>不会</b>在启动时就去连云端：
     * 模型实例是懒建的（见 {@code RuntimeModelRegistry}），
     * 因此没配 Key 的机器照样能用本地模式正常启动。
     *
     * <p>这里把"思考模式"的取值来源接上运行时开关（{@code ThinkingModeService}）：
     * 模型是懒建的，装配时读到的必须是那一刻的值 —— 否则界面上切换了开关，
     * 已建好的模型仍按旧值发请求，表现为"切了没生效"。
     *
     * @param properties   应用配置
     * @param resolver     云端端点解析器
     * @param thinkingMode 思考模式开关
     * @return 云端模型供应商
     */
    @Bean
    public CloudModelProvider cloudModelProvider(QaProperties properties,
                                                CloudEndpointResolver resolver,
                                                ThinkingModeService thinkingMode) {
        return new CloudModelProvider(properties, resolver).thinkingEnabled(thinkingMode::current);
    }

    /**
     * 云端端点解析器。
     *
     * <p>无条件注册：它同时被"云端供应商装配"和"系统状态接口 / 连通性自检"使用，
     * 而后者在本地模式下也要能回答"云端配好了没"。若只在 cloud 模式下注册，
     * 本地模式就无法回答这个问题了。
     *
     * @param properties  应用配置
     * @param environment Spring 环境（读环境变量）
     * @return 解析器
     */
    @Bean
    public CloudEndpointResolver cloudEndpointResolver(QaProperties properties, Environment environment) {
        return new CloudEndpointResolver(properties, environment);
    }

    /**
     * 对外暴露的对话模型 Bean。
     *
     * @param registry 模型注册表
     * @return 可切换的对话模型
     */
    @Bean("qaChatModel")
    public ChatModel qaChatModel(RuntimeModelRegistry registry) {
        return registry.chatModel();
    }

    /**
     * 对外暴露的流式对话模型 Bean（{@code /api/v1/chat/stream} 使用）。
     *
     * @param registry 模型注册表
     * @return 可切换的流式对话模型
     */
    @Bean("qaStreamingChatModel")
    public StreamingChatModel qaStreamingChatModel(RuntimeModelRegistry registry) {
        return registry.streamingChatModel();
    }

    /**
     * 长期记忆抽取专用模型（温度 0）。
     *
     * <p>单独一个 Bean，而不是复用 {@code qaChatModel}：抽取要求输出严格 JSON，
     * 问答模型的温度会让它偶尔加上寒暄或 markdown 代码块，解析就失败。
     *
     * @param registry 模型注册表
     * @return 可切换的抽取模型
     */
    @Bean("qaExtractChatModel")
    public ChatModel qaExtractChatModel(RuntimeModelRegistry registry) {
        return registry.extractChatModel();
    }

    /**
     * 对外暴露的向量模型 Bean（入库与检索使用）。
     *
     * <p>不参与运行时切换：换向量模型 = 换向量空间，必须重启并做维度校验
     * （原因写在 {@code RuntimeModelRegistry} 的类注释里）。
     *
     * @param registry 模型注册表
     * @return 向量模型
     */
    @Bean("qaEmbeddingModel")
    public EmbeddingModel qaEmbeddingModel(RuntimeModelRegistry registry) {
        return registry.embeddingModel();
    }
}
