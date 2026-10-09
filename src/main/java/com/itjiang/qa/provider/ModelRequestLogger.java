package com.itjiang.qa.provider;

import com.itjiang.qa.config.QaProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模型请求抓取器：把"实际发给模型的完整消息列表"按需打进日志。
 *
 * <h2>为什么需要它（而且必须放在路由层）</h2>
 * <p>排查"模型为什么这样答"时，唯一有价值的东西是<b>真正发出去的消息列表</b>：
 * 系统提示词在不在、检索片段注入了没有、历史有没有被截断。
 * 而 LangChain4j 把"拼消息"这件事封装在内部，业务代码看不到最终结果 ——
 * 只有路由层能拿到组装完毕的 {@link ChatRequest}。
 *
 * <h2>为什么不用 {@code logRequests(true)}</h2>
 * <p>LangChain4j 自带的请求日志会打印 <b>HTTP 头</b>，云端模式下其中就有
 * {@code Authorization: Bearer sk-...} —— 开启它等于把 API Key 写进日志文件。
 * 这里只打印消息内容，不碰任何请求头，因此本地与云端都安全。
 *
 * <h2>⚠️ 它仍然是敏感开关</h2>
 * <p>消息内容包含用户提问与检索到的文档片段。默认关闭，只在抓现场时临时打开
 * （{@code qa.debug.log-model-requests: true}），抓到即关。
 * 日志级别刻意用 WARN，避免被"只看 INFO 以上"的配置过滤掉。
 *
 * @author Jiang
 */
@Component
public class ModelRequestLogger {

    private static final Logger log = LoggerFactory.getLogger(ModelRequestLogger.class);

    /** 单条消息最多打印多少字符。太长会把日志刷爆，也失去可读性。 */
    private static final int MAX_CHARS_PER_MESSAGE = 900;

    private final QaProperties properties;

    public ModelRequestLogger(QaProperties properties) {
        this.properties = properties;
    }

    /**
     * 打印一次模型请求的全部消息。
     *
     * @param label   来源标识（qa / extract / qa-stream）
     * @param request 组装完毕的请求
     */
    public void log(String label, ChatRequest request) {
        if (!properties.getDebug().isLogModelRequests() || request == null) {
            return;
        }
        StringBuilder out = new StringBuilder();
        out.append("[模型请求抓取] ").append(label)
                .append(" 消息数=").append(request.messages().size());
        int index = 0;
        for (ChatMessage message : request.messages()) {
            out.append("\n  #").append(index++).append(' ').append(describe(message));
        }
        log.warn(out.toString());
    }

    /**
     * 把一条消息渲染成"角色 + 文本"。
     *
     * @param message 消息
     * @return 可读描述
     */
    private String describe(ChatMessage message) {
        String role;
        String text;
        if (message instanceof SystemMessage system) {
            role = "system";
            text = system.text();
        } else if (message instanceof UserMessage user) {
            role = "user";
            text = user.hasSingleText() ? user.singleText() : user.toString();
        } else if (message instanceof AiMessage ai) {
            role = "assistant";
            text = ai.text();
        } else {
            role = message.type().name().toLowerCase();
            text = message.toString();
        }
        if (text == null) {
            text = "";
        }
        String shown = text.length() > MAX_CHARS_PER_MESSAGE
                ? text.substring(0, MAX_CHARS_PER_MESSAGE) + "…(共 " + text.length() + " 字)"
                : text;
        return role + "(" + text.length() + "字): " + shown;
    }
}
