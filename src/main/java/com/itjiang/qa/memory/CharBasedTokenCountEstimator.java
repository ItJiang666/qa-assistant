package com.itjiang.qa.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import org.springframework.stereotype.Component;

/**
 * 中文友好的 token 估算器。
 *
 * <p><b>为什么不用现成的 tokenizer？</b>
 * LangChain4j 的 {@code OpenAiTokenizer} 在 {@code langchain4j-open-ai} 模块里，
 * 而我们本地模式只依赖 {@code langchain4j-core} + {@code langchain4j-ollama} ——
 * 为了算个 token 数就把 OpenAI 的整套客户端拖进来，不划算。
 * 更重要的是：<b>Qwen 的 tokenizer 与 OpenAI 的并不相同</b>，
 * 用 OpenAiTokenizer 去估 Qwen 的占用，本来就是一种"看起来精确的错"。
 *
 * <p>既然无论用哪种方式都只是估算，那就不如用一个<b>可解释、可控、且对中文更准</b>的规则：
 * <ul>
 *   <li>中日韩字符：1 字 ≈ 1 token（Qwen 系对中文的压缩率大致如此）</li>
 *   <li>其余字符（英文/数字/标点）：4 字符 ≈ 1 token</li>
 *   <li>每条消息额外加 4 token 的协议开销（role、分隔符等）</li>
 * </ul>
 *
 * <p>本估算器的唯一用途是给 ChatMemory 的窗口裁剪提供依据。
 * 这里宁可<b>略微高估</b>：估多了只是少带一点历史，估少了会撞上模型上下文上限 ——
 * 后者的表现是"模型看不见前面的资料却照常编答案"，属于最隐蔽的一类故障。
 *
 * @author Jiang
 */
@Component
public class CharBasedTokenCountEstimator implements TokenCountEstimator {

    /** 每条消息在对话模板里的固定开销（role 标记、分隔符等）。 */
    private static final int PER_MESSAGE_OVERHEAD = 4;

    /** 非中文文本的压缩比：平均 4 个字符折算 1 个 token。 */
    private static final double NON_CJK_CHARS_PER_TOKEN = 4.0;

    @Override
    public int estimateTokenCountInText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjkCount = 0;
        int otherCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjkCount++;
            } else {
                otherCount++;
            }
        }
        return cjkCount + (int) Math.ceil(otherCount / NON_CJK_CHARS_PER_TOKEN);
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        if (message == null) {
            return 0;
        }
        return PER_MESSAGE_OVERHEAD + estimateTokenCountInText(extractText(message));
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        int total = 0;
        for (ChatMessage message : messages) {
            total += estimateTokenCountInMessage(message);
        }
        return total;
    }

    /**
     * 从各类消息中取出纯文本。
     *
     * <p>用 {@code switch} + 模式匹配风格的类型判断：LangChain4j 的 {@code ChatMessage}
     * 没有统一的 {@code text()} 方法，只能按类型取。取不到的一律当 0 处理 ——
     * 估算器不该因为遇到没见过的消息类型就抛异常，那会把一次正常的对话打断。
     *
     * @param message 消息
     * @return 纯文本，取不到时返回空串
     */
    private String extractText(ChatMessage message) {
        ChatMessageType type = message.type();
        if (type == ChatMessageType.USER && message instanceof UserMessage userMessage) {
            return userMessage.hasSingleText() ? userMessage.singleText() : "";
        }
        if (type == ChatMessageType.AI && message instanceof AiMessage aiMessage) {
            return aiMessage.text() == null ? "" : aiMessage.text();
        }
        if (type == ChatMessageType.SYSTEM && message instanceof SystemMessage systemMessage) {
            return systemMessage.text() == null ? "" : systemMessage.text();
        }
        if (type == ChatMessageType.TOOL_EXECUTION_RESULT
                && message instanceof ToolExecutionResultMessage toolMessage) {
            return toolMessage.text() == null ? "" : toolMessage.text();
        }
        return "";
    }

    /**
     * 判断是否为中日韩字符。
     *
     * <p>覆盖范围：基本汉字、扩展 A、兼容汉字、日文假名、韩文音节、全角标点。
     * 这些字符在 Qwen 系的 BPE 表里基本都是单 token。
     *
     * @param c 字符
     * @return 是中日韩字符返回 {@code true}
     */
    private boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // CJK 统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF)  // 兼容表意文字
                || (c >= 0x3040 && c <= 0x30FF)  // 日文假名
                || (c >= 0xAC00 && c <= 0xD7A3)  // 韩文音节
                || (c >= 0x3000 && c <= 0x303F)  // CJK 标点
                || (c >= 0xFF00 && c <= 0xFF60); // 全角字符
    }
}
