package com.itjiang.qa.service;

import com.itjiang.qa.assistant.QaAssistant;
import com.itjiang.qa.common.BusinessException;
import com.itjiang.qa.common.ErrorCode;
import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.context.UserContext;
import com.itjiang.qa.model.dto.ChatRequest;
import com.itjiang.qa.model.dto.ChatResponse;
import com.itjiang.qa.model.enums.RunMode;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

/**
 * 问答编排（开发文档 4.3.2）。
 *
 * <p>这是唯一知道"一次问答由哪些步骤组成"的地方。控制器不该知道、
 * 助手接口更不该知道 —— 编排逻辑只存在于一处，将来插入检索（P3）、
 * 长期记忆（P3.5）都只改这里。
 *
 * <h2>落库时序：为什么必须"先问模型、后写库"</h2>
 * <p>{@code JdbcChatMemoryStore} 每次提问都会从数据库读全量历史。
 * 若先写用户消息再调模型，本轮问题会在上下文里出现<b>两次</b>；
 * 反过来（先问后写）则天然正确：模型看到的是"上轮为止"的历史。
 * 代价是模型调用失败时该问题不入库 —— 这是可接受的，一次没有答案的提问
 * 留在历史里反而会污染后续上下文。
 *
 * <h2>流式与同步的差别</h2>
 * <p>流式必须<b>等流结束</b>才能落库，因为那时才知道完整回答。
 * 因此在 {@code onCompleteResponse} 回调里统一处理落库与收尾。
 *
 * @author Jiang
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** SSE 连接超时：设为 5 分钟，覆盖"用户挂着页面慢慢看"的场景。 */
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final QaAssistant assistant;
    private final QaProperties properties;
    private final ConversationService conversationService;

    public ChatService(QaAssistant assistant,
                       QaProperties properties,
                       ConversationService conversationService) {
        this.assistant = assistant;
        this.properties = properties;
        this.conversationService = conversationService;
    }

    /**
     * 同步问答：一次性拿到完整回答。
     *
     * @param request 问答请求
     * @return 问答响应
     */
    public ChatResponse ask(ChatRequest request) {
        String userId = UserContext.get();
        String question = request.question().trim();
        long startNanos = System.nanoTime();

        String conversationId = conversationService.ensureConversation(
                userId, request.conversationId(), question);

        String answer;
        try {
            answer = assistant.chat(conversationId, question);
        } catch (Exception ex) {
            throw translateFailure(ex, question);
        }
        if (answer == null || answer.isBlank()) {
            // 空回答必须当失败处理：静默的空答案看起来像界面卡住，比报错更难排查
            log.warn("模型返回空回答, conversationId={}", conversationId);
            throw new BusinessException(ErrorCode.CHAT_MODEL_ERROR,
                    "模型返回了空回答。若使用推理类模型，请确认 qa.local.think 已设为 false。");
        }

        conversationService.appendMessage(conversationId, "user", question);
        conversationService.appendMessage(conversationId, "assistant", answer);

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("问答完成: conversationId={}, 耗时 {} ms, 问题 {} 字, 回答 {} 字",
                conversationId, elapsedMs, question.length(), answer.length());

        return new ChatResponse(
                conversationId,
                answer,
                // P3 阶段填入检索来源；现在保持空列表，让前端接口契约从一开始就稳定
                List.of(),
                elapsedMs,
                RunMode.from(properties.getMode()).getValue(),
                properties.getLocal().getChatModel());
    }

    /**
     * 流式问答：逐字推送，结束后落库。
     *
     * <p><b>这是统一响应体的唯一例外</b>（开发文档 4.2 约定）：
     * SSE 是事件流，无法套 {@code {code,message,data}} 外壳。
     * 因此这里用事件名区分语义：{@code delta} 增量文本、{@code done} 收尾、{@code error} 异常。
     *
     * @param request 问答请求
     * @return SSE 发射器
     */
    public SseEmitter stream(ChatRequest request) {
        String userId = UserContext.get();
        String question = request.question().trim();
        String conversationId = conversationService.ensureConversation(
                userId, request.conversationId(), question);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        StringBuilder buffer = new StringBuilder();
        long startNanos = System.nanoTime();

        // 先推一条 meta，让前端立刻拿到 conversationId 并开始渲染，不必等第一个 token
        sendEvent(emitter, "meta", Map.of("conversationId", conversationId));

        TokenStream tokenStream = assistant.chatStream(conversationId, question);
        tokenStream
                .onPartialResponse(token -> {
                    buffer.append(token);
                    sendEvent(emitter, "delta", Map.of("text", token));
                })
                .onCompleteResponse(response -> {
                    String answer = buffer.toString();
                    try {
                        // 流结束才知道完整回答，落库必须放在这里
                        conversationService.appendMessage(conversationId, "user", question);
                        conversationService.appendMessage(conversationId, "assistant", answer);
                        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                        log.info("流式问答完成: conversationId={}, 耗时 {} ms, 回答 {} 字",
                                conversationId, elapsedMs, answer.length());
                        sendEvent(emitter, "done", Map.of(
                                "conversationId", conversationId,
                                "elapsedMs", elapsedMs,
                                "model", properties.getLocal().getChatModel()));
                    } finally {
                        emitter.complete();
                    }
                })
                .onError(error -> {
                    log.error("流式问答失败: conversationId={}", conversationId, error);
                    sendEvent(emitter, "error", Map.of("message", friendlyMessage(error)));
                    emitter.complete();
                })
                .start();

        return emitter;
    }

    /**
     * 发送一个 SSE 事件。
     *
     * <p>客户端断开时 {@code send} 会抛 {@link IOException}（例如用户关掉了页面）。
     * 这里<b>吞掉并按调试级别记录</b>：用户主动离开不是错误，
     * 若按 ERROR 记录，日志里会堆满"正常使用"产生的假故障。
     *
     * @param emitter SSE 发射器
     * @param name    事件名
     * @param data    事件数据（会被序列化为 JSON）
     */
    private void sendEvent(SseEmitter emitter, String name, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(name)
                    .data(data, org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            log.debug("SSE 推送失败（客户端可能已断开）: event={}, reason={}", name, ex.getMessage());
        }
    }

    /**
     * 把底层异常翻译成用户能看懂的业务错误。
     *
     * <p>{@code ConnectException} 对用户毫无意义，而"Ollama 连不上，请确认已启动"
     * 能让他自己解决问题 —— 绝大多数"系统坏了"的反馈，本质只是依赖没起来。
     *
     * @param ex       原始异常
     * @param question 用户问题（只记长度，不记内容，避免日志堆积用户数据）
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
        log.error("调用模型失败（未预期）: 问题长度={}", question.length(), ex);
        return new BusinessException(ErrorCode.CHAT_MODEL_ERROR, null, ex);
    }

    /**
     * 给流式错误挑一句人能看懂的话。
     *
     * @param error 原始异常
     * @return 提示文案
     */
    private String friendlyMessage(Throwable error) {
        if (hasCause(error, ConnectException.class) || hasCause(error, UnknownHostException.class)) {
            return "连接不上本地模型服务，请确认 Ollama 已启动。";
        }
        if (hasCause(error, SocketTimeoutException.class)) {
            return "模型响应超时，请稍后重试。";
        }
        return "生成回答时发生错误，请重试。";
    }

    /**
     * 判断异常链中是否包含指定类型。
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
     * 取异常链最底层的消息。
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
