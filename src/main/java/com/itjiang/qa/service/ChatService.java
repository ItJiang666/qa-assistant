package com.itjiang.qa.service;

import com.itjiang.qa.assistant.QaAssistant;
import com.itjiang.qa.common.AnswerGuard;
import com.itjiang.qa.common.BusinessException;
import com.itjiang.qa.common.ErrorCode;
import com.itjiang.qa.common.FailureTranslator;
import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.context.UserContext;
import com.itjiang.qa.model.dto.ChatRequest;
import com.itjiang.qa.model.dto.ChatResponse;
import com.itjiang.qa.model.enums.RunMode;
import com.itjiang.qa.memory.DeletedMemoryNotice;
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
    private final MetaQuestionService metaQuestionService;

    /**
     * 刚删除记忆的作废通知。
     *
     * <p>加它的直接原因是一次用户反馈："记忆删掉了还是会出现，你的修改没有起作用"。
     * 查下来长期记忆（L3）确实删干净了（数据库里是 DELETED、memoryCount=0），
     * 但<b>会话历史（L2）里模型已经按那条偏好回答过若干轮</b>，它只是在延续自己的行为 ——
     * 删 L3 删不掉 L2。所以删除发生时记一条短时通知，下一轮明确告知该偏好已作废。
     * 详见 {@link DeletedMemoryNotice}。
     */
    private final DeletedMemoryNotice deletedMemoryNotice;

    public ChatService(QaAssistant assistant,
                       QaProperties properties,
                       ConversationService conversationService,
                       LongTermMemoryService longTermMemoryService,
                       MemoryExtractService memoryExtractService,
                       MetaQuestionService metaQuestionService,
                       DeletedMemoryNotice deletedMemoryNotice) {
        this.assistant = assistant;
        this.properties = properties;
        this.conversationService = conversationService;
        this.longTermMemoryService = longTermMemoryService;
        this.memoryExtractService = memoryExtractService;
        this.metaQuestionService = metaQuestionService;
        this.deletedMemoryNotice = deletedMemoryNotice;
    }

    /**
     * 元问题 / 测试口令的流式回答。
     *
     * <p>把整段回答作为<b>一条 delta</b> 推出去，而不是逐字伪造流式效果：
     * 固定文案只有百来字，前端渲染一瞬间完成，伪造打字机效果只会让人误以为
     * "模型在思考"，反而掩盖了"这条没走模型"这个事实（回答正文里已经写明）。
     *
     * <p>事件序列与正常路径完全一致（meta → delta → done），前端不需要任何分叉。
     *
     * @param emitter        SSE 发射器
     * @param conversationId 会话 ID
     * @param question       用户问题
     * @param kind           命中类别
     * @param startNanos     起始纳秒
     */
    private void emitMetaAnswer(SseEmitter emitter, String conversationId, String question,
                                String kind, long startNanos) {
        String answer = metaQuestionService.reply(kind);
        try {
            sendEvent(emitter, "delta", Map.of("text", answer));
            conversationService.appendMessage(conversationId, "user", question);
            conversationService.appendMessage(conversationId, "assistant", answer);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("元问题短路回答: conversationId={}, 类别={}, 耗时 {} ms（未调用模型）",
                    conversationId, kind, elapsedMs);
            sendEvent(emitter, "done", Map.of(
                    "conversationId", conversationId,
                    "elapsedMs", elapsedMs,
                    "model", "（固定回复，未调用模型）",
                    "sources", List.of()));
        } catch (Exception ex) {
            log.warn("元问题回答推送失败: conversationId={}, 原因={}", conversationId, ex.getMessage());
        } finally {
            emitter.complete();
        }
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

        // 元问题 / 测试口令走短路：不进检索、不调模型（原因见 MetaQuestionService 类注释）。
        // 放在 ensureConversation 之后：会话仍然要建出来，回答才有一条完整的记录。
        String metaKind = metaQuestionService.match(question);
        if (metaKind != null) {
            return answerMetaQuestion(conversationId, question, metaKind, startNanos);
        }

        // L3 长期记忆：跨会话，由我们自己查好后传给助手（框架不管这件事）
        String longTermMemory = longTermMemoryService.loadForPrompt(userId);

        String answer;
        try {
            // 请求级检索参数（topK / minScore）。必须在调用模型前设置：检索发生在
            // chat(...) 内部，且就在当前线程上（单查询、单检索器的同步路径），
            // 因此 ThreadLocal 覆盖对检索可见。为 null 时表示沿用全局配置。
            RecordingContentRetriever.applyOverrides(request.topK(), request.minScore());
            answer = assistant.chat(conversationId, composeUserMessage(userId, question, longTermMemory));
        } catch (Exception ex) {
            // 异常路径也要清掉检索记录：ThreadLocal 会跟着复用的线程活到下一个请求，
            // 那会导致下一个问题引用到毫不相干的文档，而且随机复现
            RecordingContentRetriever.clear();
            throw translateFailure(ex, question);
        } finally {
            // 与 clear() 同理：覆盖参数也必须清理，否则会污染线程上的下一个请求
            RecordingContentRetriever.clearOverrides();
        }
        // 取出本次检索命中作为"引用来源"。框架只把内容注入 Prompt，不会回传，
        // 所以这里从记录器取 —— 这正是 RecordingContentRetriever 存在的理由
        List<ChatResponse.Source> sources = toSources(RecordingContentRetriever.drain());

        if (AnswerGuard.isEmpty(answer)) {
            // 空回答必须当失败处理：静默的空答案看起来像界面卡住，比报错更难排查。
            // 注意判据是 AnswerGuard 而不是 isBlank —— 实测出现过回答为 "[]" 的情况，
            // isBlank 抓不到它，于是两个方括号被当成正式回答写进了对话历史。
            log.warn("模型返回空回答, conversationId={}, 原始内容={}", conversationId, answer);
            throw new BusinessException(ErrorCode.CHAT_MODEL_ERROR, AnswerGuard.emptyAnswerMessage());
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
     * 元问题 / 测试口令的同步回答。
     *
     * <p>与正常问答的差别只在"答案从哪来"：检索、模型、空回答守卫都不参与；
     * <b>落库与返回结构完全一致</b>，这样前端与审计都看不出两条路径的区别，
     * 用户点开会话也不会看到"问过但没答"的空洞。
     *
     * <p><b>不触发长期记忆抽取</b>：元问题里不可能含"关于用户的偏好或事实"，
     * 白跑一次抽取模型既费额度又可能抽错东西记下来。
     *
     * @param conversationId 会话 ID
     * @param question       用户问题
     * @param kind           命中类别
     * @param startNanos     起始纳秒（与正常路径共用同一套耗时口径）
     * @return 问答响应（引用来源为空）
     */
    private ChatResponse answerMetaQuestion(String conversationId, String question,
                                           String kind, long startNanos) {
        String answer = metaQuestionService.reply(kind);
        conversationService.appendMessage(conversationId, "user", question);
        conversationService.appendMessage(conversationId, "assistant", answer);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("元问题短路回答: conversationId={}, 类别={}, 耗时 {} ms（未调用模型）",
                conversationId, kind, elapsedMs);
        return new ChatResponse(
                conversationId,
                answer,
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
        // 先推一条 meta，让前端立刻拿到 conversationId 并开始渲染，不必等第一个 token
        sendEvent(emitter, "meta", Map.of("conversationId", conversationId));

        long startNanos = System.nanoTime();

        // 元问题 / 测试口令走短路（与同步路径同一判据，见 MetaQuestionService）。
        // 直接推一条 delta + done 收掉：前端不必为它分叉，
        // 因为"整段一次到达"本来就是流式协议允许的一种形态。
        String metaKind = metaQuestionService.match(question);
        if (metaKind != null) {
            emitMetaAnswer(emitter, conversationId, question, metaKind, startNanos);
            return emitter;
        }

        String longTermMemory = longTermMemoryService.loadForPrompt(userId);
        StringBuilder buffer = new StringBuilder();
        // 流式场景下检索结果通过 onRetrieved 回调送进来，用 AtomicReference 承接
        // （回调可能在任何线程上执行，普通变量会有可见性问题）
        AtomicReference<List<ChatResponse.Source>> sourcesRef = new AtomicReference<>(List.of());

        TokenStream tokenStream;
        try {
            // 与同步路径同理：检索在 chatStream(...) 内部、返回流之前同步完成，
            // 所以覆盖参数要在这里设置，拿到流之后立刻清掉
            RecordingContentRetriever.applyOverrides(request.topK(), request.minScore());
            tokenStream = assistant.chatStream(conversationId, composeUserMessage(userId, question, longTermMemory));
        } catch (Exception ex) {
            // ★ 关键：检索与首次调用都在返回流之前同步发生，因此这里能拦到
            //   "Key 无效 / 余额不足 / 连不上" 这类一上来就失败的情况。
            //
            //   不拦的后果（P6 演练实测）：异常冒泡到全局处理器 → HTTP 500 +
            //   业务码 9000「系统内部错误」。而前端是按 SSE 事件渲染的，
            //   它只看到"请求失败（HTTP 500）"，连是 Key 错了还是服务崩了都分不清。
            //   改成推一条 error 事件，就能复用与同步路径完全相同的翻译结果。
            RecordingContentRetriever.clear();
            String message = friendlyMessage(ex);
            log.warn("流式问答在建立连接阶段失败: conversationId={}, 原因={}", conversationId, FailureTranslator.rootMessage(ex));
            try {
                sendEvent(emitter, "error", Map.of("message", message));
            } catch (Exception sendFailure) {
                // 推不出去只能放弃：此时连接可能已经断了，再抛也没人接
                log.warn("错误事件推送失败: conversationId={}", conversationId);
            }
            emitter.complete();
            return emitter;
        } finally {
            RecordingContentRetriever.clearOverrides();
        }
        tokenStream
                .onRetrieved(contents -> sourcesRef.set(toSources(contents)))
                .onPartialResponse(token -> {
                    buffer.append(token);
                    sendEvent(emitter, "delta", Map.of("text", token));
                })
                .onCompleteResponse(response -> {
                    String answer = buffer.toString();
                    try {
                        // 空回答守卫：把 [] / {} / 纯标点这类"没说出任何东西"的回答当失败处理。
                        // 关键是**不要落库** —— 否则两个方括号会进对话历史，
                        // 之后每一轮都被当作"我之前说过的话"再喂回模型。
                        if (AnswerGuard.isEmpty(answer)) {
                            log.warn("流式回答为空, conversationId={}, 原始内容={}", conversationId, answer);
                            sendEvent(emitter, "error", Map.of("message", AnswerGuard.emptyAnswerMessage()));
                            return;
                        }
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
     * @param userId         用户标识（用于取"刚删除记忆"的作废通知）
     * @param question       原始问题
     * @param longTermMemory 已拼好的长期记忆段落（可能为"（暂无）"）
     * @return 发给模型的用户消息
     */
    private String composeUserMessage(String userId, String question, String longTermMemory) {
        StringBuilder sb = new StringBuilder(question);

        if (longTermMemory != null && !longTermMemory.isBlank()
                && !LongTermMemoryService.NO_MEMORY.equals(longTermMemory)) {
            sb.append("\n\n【关于你的长期记忆】以下是跨会话记住的、关于你的偏好与背景：\n")
                    .append(longTermMemory);
        }

        // ★ 刚删除记忆的作废通知（见 DeletedMemoryNotice）。
        //
        //   为什么必须加：删掉 L3 的记忆数据**删不掉 L2 对话历史里已经形成的行为**。
        //   实测场景 —— 用户在会话里说"每次回答后加个喵~"、模型照做；
        //   之后用户删掉这条记忆并在**同一个会话**里继续提问，模型仍带"喵~"，
        //   于是用户认定"记忆删了没用"。而数据库里那条记忆确实是 DELETED，
        //   问题在于对话历史里模型一直在那么做，它只是延续自己前几轮的行为。
        //
        //   放在用户消息里而不是系统提示词：紧挨着本轮问题，约束力更强；
        //   且只在删除后的短时间窗内出现，不会长期占用上下文。
        String notice = deletedMemoryNotice.take(userId);
        if (notice != null) {
            sb.append(notice);
        }
        return sb.toString();
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
     * <p>映射规则集中在 {@link FailureTranslator}：那是"故障 → 用户看到什么"的唯一出处，
     * 同一个故障在同步与流式两条路径必须给同一句话，用户才不会以为是两个问题。
     *
     * @param ex       原始异常
     * @param question 用户问题（只记长度，不记内容，避免日志堆积用户数据）
     * @return 翻译后的业务异常
     */
    private BusinessException translateFailure(Exception ex, String question) {
        return FailureTranslator.toBusinessException(ex, question);
    }

    /**
     * 给流式错误挑一句人能看懂的话。
     *
     * @param error 原始异常
     * @return 提示文案
     */
    private String friendlyMessage(Throwable error) {
        String message = FailureTranslator.message(error);
        return message == null ? "生成回答时发生错误，请重试。" : message;
    }
}
