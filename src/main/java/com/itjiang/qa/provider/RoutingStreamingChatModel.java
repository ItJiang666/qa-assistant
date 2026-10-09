package com.itjiang.qa.provider;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 可切换的流式对话模型：与 {@link RoutingChatModel} 同理，只是扩展点换成了
 * {@code doChat(request, handler)}。
 *
 * <p>流式路径必须与同步路径切到同一个模型，否则会出现
 * 「同步问答走云端、流式问答还在本地」这种自相矛盾的状态 —— 界面上的开关
 * 是一个，两边的取值就必须来自同一处（见 {@code RuntimeModelRegistry}）。
 *
 * @author Jiang
 */
public class RoutingStreamingChatModel implements StreamingChatModel {

    private final Supplier<StreamingChatModel> delegate;
    private final String label;

    /**
     * @param delegate 当前生效模型的来源，每次调用时求值
     * @param label    用途描述，仅用于日志与排错
     */
    public RoutingStreamingChatModel(Supplier<StreamingChatModel> delegate, String label) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.label = label;
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        delegate.get().chat(request, handler);
    }

    /**
     * @return 当前生效模型的默认请求参数
     */
    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.get().defaultRequestParameters();
    }

    /**
     * @return 当前生效模型的供应商
     */
    @Override
    public dev.langchain4j.model.ModelProvider provider() {
        return delegate.get().provider();
    }

    @Override
    public String toString() {
        return "RoutingStreamingChatModel(" + label + ")";
    }
}
