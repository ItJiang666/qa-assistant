package com.itjiang.qa.service;

import com.itjiang.qa.assistant.QaAssistant;
import com.itjiang.qa.common.BusinessException;
import com.itjiang.qa.common.ErrorCode;
import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.context.UserContext;
import com.itjiang.qa.model.dto.ChatRequest;
import com.itjiang.qa.model.dto.ChatResponse;
import com.itjiang.qa.model.enums.RunMode;
import com.itjiang.qa.rag.RecordingContentRetriever;
import com.itjiang.qa.rag.SegmentMetadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
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
import java.util.concurrent.atomic.AtomicReference;

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

    /** 引用来源摘要的最大字符数。 */
    private static final int SNIPPET_MAX_LENGTH = 200;

    private final QaAssistant assistant;
    private final QaProperties properties;
    private final ConversationService conversationService;
    private final LongTermMemoryService longTermMemoryService;
    private final MemoryExtractService memoryExtractService;

    public ChatService(QaAssistant assistant,
                       QaProperties properties,
                       ConversationService conversationService,
                       LongTermMemoryService longTermMemoryService,
                       MemoryExtractService memoryExtractService) {
        this.assistant = assistant;
        this.properties = properties;
        this.conversationService = conversationService;
        this.longTermMemoryService = longTermMemoryService;
        this.memoryExtractService = memoryExtractService;
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

        // L3 长期记忆：跨会话，由我们自己查好后传给助手（框架不管这件事）
        String longTermMemory = longTermMemoryService.loadForPrompt(userId);

        String answer;
        try {
            answer = assistant.chat(conversationId, composeUserMessage(question, longTermMemory));
        } catch (Exception ex) {
            // 异常路径也要清掉检索记录：ThreadLocal 会跟着复用的线程活到下一个请求，
            // 那会导致下一个问题引用到毫不相干的文档，而且随机复现
            RecordingContentRetriever.clear();
            throw translateFailure(ex, question);
        }
        // 取出本次检索命中作为"引用来源"。框架只把内容注入 Prompt，不会回传，
        // 所以这里从记录器取 —— 这正是 RecordingContentRetriever 存在的理由
        List<ChatResponse.Source> sources = toSources(RecordingContentRetriever.drain());

        if (answer == null || answer.isBlank()) {
            // 空回答必须当失败处理：静默的空答案看起来像界面卡住，比报错更难排查
            log.warn("模型返回空回答, conversationId={}", conversationId);
            throw new BusinessException(ErrorCode.CHAT_MODEL_ERROR,
                    "模型返回了空回答。若使用推理类模型，请确认 qa.local.think 已设为 false。");
        }

        conversationService.appendMessage(conversationId, "user", question);
        conversationService.appendMessage(conversationId, "assistant", answer);

        // L3 写入：异步抽取，绝不阻塞返回。
        // userId 必须显式传进去 —— ThreadLocal 不会传播到异步线程。
        // 未识别用户（无 X-User-Id）时 userId 为 null，extractAsync 内部会直接跳过。
        if (request.rememberOrDefault()) {
            memoryExtractService.extractAsync(userId, conversationId, question, answer);
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("问答完成: conversationId={}, 耗时 {} ms, 问题 {} 字, 回答 {} 字",
                conversationId, elapsedMs, question.length(), answer.length());

        return new ChatResponse(
                conversationId,
                answer,
                sources,
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
        String longTermMemory = longTermMemoryService.loadForPrompt(userId);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        StringBuilder buffer = new StringBuilder();
        // 流式场景下检索结果通过 onRetrieved 回调送进来，用 AtomicReference 承接
        // （回调可能在任何线程上执行，普通变量会有可见性问题）
        AtomicReference<List<ChatResponse.Source>> sourcesRef = new AtomicReference<>(List.of());
        long startNanos = System.nanoTime();

        // 先推一条 meta，让前端立刻拿到 conversationId 并开始渲染，不必等第一个 token
        sendEvent(emitter, "meta", Map.of("conversationId", conversationId));

        TokenStream tokenStream = assistant.chatStream(conversationId, composeUserMessage(question, longTermMemory));
        tokenStream
                .onRetrieved(contents -> sourcesRef.set(toSources(contents)))
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
                        // 流式路径同样触发 L3 抽取：必须在流结束后，
                        // 因为那时才知道完整回答长什么样
                        if (request.rememberOrDefault()) {
                            memoryExtractService.extractAsync(userId, conversationId, question, answer);
                        }
                        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                        log.info("流式问答完成: conversationId={}, 耗时 {} ms, 回答 {} 字, 引用 {} 条",
                                conversationId, elapsedMs, answer.length(), sourcesRef.get().size());
                        sendEvent(emitter, "done", Map.of(
                                "conversationId", conversationId,
                                "elapsedMs", elapsedMs,
                                "model", properties.getLocal().getChatModel(),
                                "sources", sourcesRef.get()));
                    } finally {
                        RecordingContentRetriever.clear();
                        emitter.complete();
                    }
                })
                .onError(error -> {
                    log.error("流式问答失败: conversationId={}", conversationId, error);
                    RecordingContentRetriever.clear();
                    sendEvent(emitter, "error", Map.of("message", friendlyMessage(error)));
                    emitter.complete();
                })
                .start();

        return emitter;
    }

    /**
     * 把长期记忆拼进用户消息。
     *
     * <h2>为什么拼在用户消息里，而不是系统消息里</h2>
     * <ol>
     *   <li><b>系统消息无法按请求插值。</b> 它由 {@code JdbcChatMemoryStore} 统一供给
     *       （见 {@code Prompts} 里的完整说明），那里没有模板引擎，
     *       而长期记忆本来就是每次提问才查出来的。</li>
     *   <li><b>紧挨着问题的位置约束力更强。</b> 已用对照实验验证：
     *       同样一段"回答控制在三句话以内"的偏好，放在系统消息里与放在问题后面
     *       都能被模型遵守；但用户消息里的那一份不会被检索片段挤到远处。</li>
     * </ol>
     *
     * <p>注意落库时保存的仍然是<b>原始问题</b>（见 {@code ask} 与 {@code stream}），
     * 这一段落只在本次请求生效，不会被写进对话历史、也不会污染 L2 记忆。
     *
     * @param question       原始问题
     * @param longTermMemory 已拼好的长期记忆段落（可能为"（暂无）"）
     * @return 发给模型的用户消息
     */
    private String composeUserMessage(String question, String longTermMemory) {
        if (longTermMemory == null || longTermMemory.isBlank()
                || LongTermMemoryService.NO_MEMORY.equals(longTermMemory)) {
            return question;
        }
        return question
                + "\n\n【关于你的长期记忆】以下是跨会话记住的、关于你的偏好与背景：\n"
                + longTermMemory;
    }

    /**
     * 检索命中 → 引用来源。
     *
     * <p>每一条来源都带上文档名与原文片段，让用户能<b>点开核对</b>。
     * 这不是锦上添花：只能看到一段没有出处的文字，用户就只能靠猜要不要信；
     * 能核对，错误答案才会被发现。
     *
     * @param contents 检索命中
     * @return 引用来源列表
     */
    private List<ChatResponse.Source> toSources(List<Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return List.of();
        }
        return contents.stream()
                // 过滤掉"检索为空"时注入的占位内容：它不是真的文档片段，
                // 展示成引用来源会让用户以为知识库里真有这么一份文档
                .filter(content -> !RecordingContentRetriever.isPlaceholder(content))
                .map(content -> {
            TextSegment segment = content.textSegment();
            Long documentId = segment.metadata().getLong(SegmentMetadata.DOCUMENT_ID);
            String fileName = segment.metadata().getString(SegmentMetadata.FILE_NAME);

            // 相似度得分放在 Content 的 metadata 里，而不是片段自己的 metadata 里
            Double score = null;
            Object rawScore = content.metadata().get(ContentMetadata.SCORE);
            if (rawScore instanceof Number number) {
                score = number.doubleValue();
            }
            return new ChatResponse.Source(documentId, fileName, snippet(segment.text()), score);
        }).toList();
    }

    /**
     * 截取片段摘要。
     *
     * <p>片段本身有 chunkSize（默认 500）那么长，整段返回会让响应体膨胀数倍，
     * 而前端展示引用来源时只需要一小段。真要看全文，用户可以点进原文档。
     *
     * @param text 片段全文
     * @return 摘要文本
     */
    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.strip();
        return normalized.length() <= SNIPPET_MAX_LENGTH
                ? normalized
                : normalized.substring(0, SNIPPET_MAX_LENGTH) + "…";
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
