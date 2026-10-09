package com.itjiang.qa.provider;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 可切换的对话模型：把调用转发给"当前生效"的那个真实模型。
 *
 * <h2>为什么用委托，而不是切换时重建 AiService</h2>
 * <p>因为需求是"界面上点一下就从本地切到云端"，而 {@code @AiService} 生成的助手
 * 是在启动时按 Bean 装配好的 —— 要重建它，就得放弃注解式装配、改成手写
 * {@code AiServices.builder(...)} 并在切换时把整个助手对象换掉，牵动面很大
 * （检索器、记忆提供者都要跟着重建）。
 *
 * <p>而 LangChain4j 1.20 的 {@code ChatModel} 只有 {@code doChat} 一个真正的扩展点
 * （其余全是 {@code default} 方法），所以"换模型"可以简化成"换一个引用"：
 * 助手对象始终持有一个 {@code RoutingChatModel}，谁来干活由它每次调用时现查。
 * <b>装配方式不动，切换能力却有了</b>，这是这次实现里最划算的一处设计。
 *
 * <h2>线程安全</h2>
 * <p>切换发生在 HTTP 线程（用户点按钮），调用发生在 SSE 的工作线程，
 * 因此委托来源用一个每次调用都重新求值的 {@link Supplier}，不缓存。
 * 供应商那边自己保证"读到的引用是稳定的"（{@code volatile} + 不可变实例）。
 *
 * @author Jiang
 */
public class RoutingChatModel implements ChatModel {

    private final Supplier<ChatModel> delegate;
    private final String label;

    /**
     * @param delegate 当前生效模型的来源，每次调用时求值
     * @param label    用途描述，仅用于日志与排错
     */
    public RoutingChatModel(Supplier<ChatModel> delegate, String label) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.label = label;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return delegate.get().chat(request);
    }

    /**
     * 参数必须透传真实模型的，否则框架取默认参数时会拿到空值。
     *
     * @return 当前生效模型的默认请求参数
     */
    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.get().defaultRequestParameters();
    }

    /**
     * 供应商标识同样透传：它决定消息格式等细节，不能随便报一个。
     *
     * @return 当前生效模型的供应商
     */
    @Override
    public dev.langchain4j.model.ModelProvider provider() {
        return delegate.get().provider();
    }

    @Override
    public String toString() {
        return "RoutingChatModel(" + label + ")";
    }
}
