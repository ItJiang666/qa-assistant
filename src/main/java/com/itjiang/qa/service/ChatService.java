package com.itjiang.qa.service;

import com.itjiang.qa.assistant.QaAssistant;
import com.itjiang.qa.common.BusinessException;
import com.itjiang.qa.common.ErrorCode;
import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.model.dto.ChatRequest;
import com.itjiang.qa.model.dto.ChatResponse;
import com.itjiang.qa.model.enums.RunMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 问答编排（开发文档 4.3.2）。
 *
 * <p>这是唯一知道"一次问答由哪些步骤组成"的地方。控制器不该知道、
 * 助手接口更不该知道 —— 编排逻辑只应存在于一处，否则加一步（比如记忆抽取）
 * 就要在多个地方补代码。
 *
 * <p>本阶段（P1a）只完成最小闭环：<b>调用模型 → 组装响应</b>。
 * 会话记忆落库在 P1b，检索引用在 P3，长期记忆在 P3.5。
 *
 * @author Jiang
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final QaAssistant assistant;
    private final QaProperties properties;

    public ChatService(QaAssistant assistant, QaProperties properties) {
        this.assistant = assistant;
        this.properties = properties;
    }

    /**
     * 执行一次问答。
     *
     * @param request 问答请求
     * @return 问答响应
     */
    public ChatResponse ask(ChatRequest request) {
        long startNanos = System.nanoTime();
        String question = request.question().trim();

        String answer;
        try {
            answer = assistant.chat(question);
        } catch (Exception ex) {
            throw translateFailure(ex, question);
        }

        if (answer == null || answer.isBlank()) {
            // 空回答必须当成失败处理，不能当成"模型没什么可说的"返回给用户。
            // 静默的空答案看起来像界面卡住，比报错更难排查。
            log.warn("模型返回空回答，问题长度={}", question.length());
            throw new BusinessException(ErrorCode.CHAT_MODEL_ERROR,
                    "模型返回了空回答。若使用推理类模型（如 deepseek-r1），请改用 qwen3.5:9b 这类通用对话模型。");
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("问答完成: 耗时 {} ms, 问题 {} 字, 回答 {} 字",
                elapsedMs, question.length(), answer.length());

        return new ChatResponse(
                request.conversationId(),
                answer,
                // P3 阶段填入检索来源；现在保持空列表，让前端接口契约从一开始就稳定
                List.of(),
                elapsedMs,
                RunMode.from(properties.getMode()).getValue(),
                properties.getLocal().getChatModel());
    }

    /**
     * 把底层异常翻译成用户能看懂的业务错误。
     *
     * <p>这一步的价值在于：{@code ConnectException} 对用户毫无意义，
     * 而"Ollama 连不上，请确认已启动"能让他自己解决问题。
     * 绝大多数"系统坏了"的反馈，本质都只是依赖没起来。
     *
     * @param ex       原始异常
     * @param question 用户问题（只记长度，不记内容，避免日志里堆积用户数据）
     * @return 翻译后的业务异常
     */
    private BusinessException translateFailure(Exception ex, String question) {
        if (hasCause(ex, ConnectException.class) || hasCause(ex, UnknownHostException.class)) {
            log.warn("调用模型失败（连接不上）: {}", rootMessage(ex));
            return new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "连接不上本地模型服务。请确认 Ollama 已启动（默认 http://localhost:11434）。", ex);
        }
        if (hasCause(ex, SocketTimeoutException.class)) {
            log.warn("调用模型超时: {}", rootMessage(ex));
            return new BusinessException(ErrorCode.CHAT_MODEL_ERROR,
                    "模型响应超时。首次提问需要加载模型，可能耗时较久；若持续超时请检查显存是否充足。", ex);
        }
        // 这里必须打全堆栈：不是预期内的失败，日志要留下足够线索
        log.error("调用模型失败（未预期）: 问题长度={}", question.length(), ex);
        return new BusinessException(ErrorCode.CHAT_MODEL_ERROR, null, ex);
    }

    /**
     * 判断异常链中是否包含指定类型的异常。
     *
     * @param throwable 起始异常
     * @param type      目标类型
     * @return 命中返回 {@code true}
     */
    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        // 限制深度，防止异常链成环导致死循环
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 取异常链最底层的消息，用于日志。
     *
     * @param throwable 起始异常
     * @return 最底层异常消息
     */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current.getCause() != null && depth < 10; depth++) {
            current = current.getCause();
        }
        return current.getMessage();
    }
}
