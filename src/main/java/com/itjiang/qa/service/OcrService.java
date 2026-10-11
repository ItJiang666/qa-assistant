package com.itjiang.qa.service;

import com.itjiang.qa.common.BusinessException;
import com.itjiang.qa.common.ErrorCode;
import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.mapper.DocumentMapper;
import com.itjiang.qa.model.dto.OcrPreviewResponse;
import com.itjiang.qa.model.dto.OcrResult;
import com.itjiang.qa.model.entity.Document;
import com.itjiang.qa.ocr.OcrEngine;
import com.itjiang.qa.ocr.OcrException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 扫描件 OCR 的编排：识别 → 人工确认 → 入库。
 *
 * <h2>为什么必须分成"识别"和"确认"两步</h2>
 * <p>OCR 一定会有错字，而错了的文本一旦入库就很难收拾（它会被检索、被引用、被当成依据）。
 * 所以流程刻意断开：{@link #recognize} 只把结果<b>暂存在服务端内存</b>并回一个预览，
 * 用户在界面上核对后调 {@link #confirm} 才真正分块入库。
 *
 * <h2>为什么暂存要在服务端而不是回给前端再传回来</h2>
 * <p>三个原因：① 大文件识别出几万字，来回传会让响应体与请求体都很笨重；
 * ② 文本经前端内存与浏览器历史一遍，等于多一处泄露面；
 * ③ 前端只需展示预览，没有理由拿到全文。
 *
 * <p>暂存带 TTL：识别是重活（50 页约 2 分钟），结果不能永久占内存；
 * 也不能一存就过期（用户可能开着对话框去核对很久）。默认 30 分钟。
 *
 * <h2>入库后写什么留痕</h2>
 * <p>把来源写进 {@code error_message}（该字段本来就是"留痕给用户看"的用途）：
 * <pre>文本来自本机 OCR 识别（rapidocr，置信度 0.97，3/3 页）</pre>
 * 这样用户在文档列表里一眼能看出"这份文档的正文是机器识别的近似文本，不是原件原文"。
 * 刻意<b>不新增数据库列</b>：那会引入一次 schema 迁移，而当前没有任何功能需要按此筛选。
 *
 * @author Jiang
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    /** 识别结果的暂存时长。 */
    private static final Duration PENDING_TTL = Duration.ofMinutes(30);

    private final DocumentMapper documentMapper;
    private final DocumentIngestService ingestService;
    private final OcrEngine ocrEngine;
    private final QaProperties properties;

    /**
     * 待确认的识别结果：documentId → (文本 + 元信息 + 产生时刻)。
     *
     * <p>用内存而不是落库：这是<b>一次性的中间态</b>，用户不确认就该消失。
     * 落库还要考虑清理，而内存 Map 天然随进程消失 —— 重启后要求重新识别是合理行为，
     * 因为"未确认过的东西"本就不该被持久化。
     */
    private final Map<Long, Pending> pendingResults = new ConcurrentHashMap<>();

    /**
     * 正在识别中的文档 ID。
     *
     * <p>用途是<b>防并发</b>：自动识别要跑约 10 秒（3 页扫描件），
     * 用户完全可能等不及就手动点一下「识别文字」—— 没有这道保护就会同时起两个
     * Python 进程读同一个文件，结果一样但白烧一遍 CPU，日志里还会出现两条
     * 互相矛盾的耗时。用 {@code putIfAbsent} 而不是 {@code contains} + {@code put}：
     * 后者两步之间存在竞态窗口，而这里恰恰就是要堵住竞态。
     */
    private final Map<Long, Boolean> inFlight = new ConcurrentHashMap<>();

    public OcrService(DocumentMapper documentMapper,
                      DocumentIngestService ingestService,
                      OcrEngine ocrEngine,
                      QaProperties properties) {
        this.documentMapper = documentMapper;
        this.ingestService = ingestService;
        this.ocrEngine = ocrEngine;
        this.properties = properties;
    }

    /**
     * 查询本机 OCR 是否可用（前端据此决定按钮是可用、置灰还是给安装指引）。
     *
     * @return 可用性状态
     */
    public OcrEngine.Availability availability() {
        return ocrEngine.availability();
    }

    /**
     * 对一个文档执行 OCR 识别（**不入库**）。
     *
     * <p>若该文档已有未过期的待确认结果，<b>直接返回它，不重跑识别</b>。
     * 这条分支是给"自动识别完成后用户点进来看"准备的：识别一份 3 页扫描件实测约 10 秒，
     * 让用户白白再等一次没有意义。
     *
     * @param documentId 文档 ID
     * @return 识别预览
     */
    public OcrPreviewResponse recognize(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException(ErrorCode.DOC_NOT_FOUND);
        }
        requireRecognizableType(doc);

        // 已有待确认结果 → 直接回放。顺带清掉已过期的条目，避免内存里留死数据
        // （用户点了识别却从不确认的话，不清理就会一直占着）。
        Pending cached = takeValidPending(documentId);
        if (cached != null) {
            log.info("复用未过期的识别结果，跳过重复识别: id={}, 文件={}", documentId, doc.getFileName());
            return toPreview(documentId, doc.getFileName(), toResult(cached));
        }

        return toPreview(documentId, doc.getFileName(), runOcr(doc));
    }

    /**
     * 自动识别：解析不出文字的文件（扫描件 / 图片）在入库流程里被自动跑一次 OCR。
     *
     * <h2>为什么方法体这么薄</h2>
     * <p>它只做"该不该跑"的判断与转发，真正的识别仍然走 {@link #runOcr} ——
     * 与手动点「识别文字」是<b>同一条代码路径</b>。若为了自动识别另写一份，
     * 两边迟早会出现"手动能识别、自动识别失败"这种只在一条路上复现的问题。
     *
     * <h2>为什么不直接调 {@link #recognize}</h2>
     * <p>{@code recognize} 需要传入文档 ID 再回查一次库，而调用方
     * （{@code DocumentIngestService}）手里已经有实体了；更关键的是
     * {@code DocumentIngestService} 与 {@code OcrService} 之间<b>不能形成依赖环</b>
     * （前者持有后者，见 {@link #stash} 的说明）。这个方法就是那个单向入口。
     *
     * @param doc 已经解析失败、且扩展名可识别的文档
     * @return 识别结果；扩展名不可识别时返回 {@code null}（调用方按普通失败处理）
     * @throws OcrException 识别本身失败（依赖缺失、文件损坏等），由调用方翻译成失败原因
     */
    public OcrResult recognizeForIngest(Document doc) {
        if (!isRecognizableType(doc)) {
            return null;
        }
        Path file = Path.of(doc.getFilePath());
        if (!Files.isRegularFile(file)) {
            throw new OcrException(ErrorCode.DOC_NOT_FOUND,
                    "文件已不在磁盘上（" + doc.getFilePath() + "），请重新上传。");
        }
        return runOcr(doc);
    }

    /**
     * 暂存一次识别结果，供用户随后确认入库。
     *
     * <p>自动识别路径需要它：识别在 {@code DocumentIngestService} 里发生，
     * 但"待确认结果"这份状态归本类管。把暂存做成公开入口，就能让
     * {@code DocumentIngestService → OcrService} 保持<b>单向依赖</b>；
     * 反过来若让 OcrService 去回调入库流程，就成了循环依赖，Spring 启动会直接失败。
     *
     * @param documentId 文档 ID
     * @param result     识别结果
     * @return 给前端用的预览
     */
    public OcrPreviewResponse stash(Long documentId, OcrResult result) {
        Pending pending = new Pending(
                result.text(), Instant.now(), result.engine(), result.engineVersion(),
                result.totalPages(), result.pagesWithText(), result.imageCount(),
                result.imagesWithText(), result.chars(), result.avgConfidence(),
                result.elapsedMs(), result.preview());
        pendingResults.put(documentId, pending);
        // 刻意**不在这里记日志**：自动识别路径下 runOcr 已经记过一条"识别完成"，
        // 这里再记一条会让日志里出现两条耗时相同、内容几乎一样的记录，
        // 排查时极易误判成"识别跑了两遍"。方法名叫 stash，就只做暂存这一件事。
        return toPreview(documentId, null, result, pending);
    }

    /**
     * 该文档当前是否有未过期的待确认识别结果。
     *
     * <p>文档列表接口用它填充 {@code ocrPending} 字段，界面据此把状态显示成
     * "识别完成，待确认"而不是一个红色的"失败"—— 后者会让用户以为出了故障，
     * 进而重复上传或重复点识别。
     *
     * @param documentId 文档 ID
     * @return 有可确认的结果时返回 {@code true}
     */
    public boolean hasPending(Long documentId) {
        return takeValidPending(documentId) != null;
    }

    /**
     * 该文档的扩展名是否值得走 OCR。
     *
     * @param doc 文档实体
     * @return 可识别时返回 {@code true}
     */
    public boolean isRecognizableType(Document doc) {
        String type = doc.getFileType() == null ? "" : doc.getFileType().toLowerCase(Locale.ROOT);
        return recognizableExtensions().contains(type);
    }

    /**
     * 校验扩展名可识别，否则抛出可照做的错误。
     *
     * @param doc 文档实体
     */
    private void requireRecognizableType(Document doc) {
        if (isRecognizableType(doc)) {
            return;
        }
        String type = doc.getFileType() == null ? "" : doc.getFileType().toLowerCase(Locale.ROOT);
        // OCR 只对"没有文字层"的文件有意义：扫描版 PDF 与图片。
        // docx/txt 本来就带文字层，走普通解析路径即可 —— 对它们做 OCR 是把
        // 清晰的原文换成带错字的猜测，纯属帮倒忙（见 QaProperties.Ocr 的说明）。
        throw new BusinessException(ErrorCode.PARAM_INVALID,
                "「识别文字」只对扫描版 PDF 和图片有效（当前文件类型：" + type + "）。"
                        + "其它格式本来就带文字层，直接上传即可被解析。");
    }

    /**
     * 真正跑一次 OCR 并暂存结果。
     *
     * <p><b>同一文档的并发保护</b>：自动识别与用户手动点击可能同时发生
     * （自动识别要跑约 10 秒，用户完全可能等不及就自己点一下）。
     * 没有这道保护就会同时起两个 Python 进程读同一个文件 ——
     * 结果一样但白烧一遍 CPU，且日志里出现两条互相矛盾的耗时。
     *
     * @param doc 文档实体
     * @return 识别结果
     */
    private OcrResult runOcr(Document doc) {
        Long documentId = doc.getId();
        if (inFlight.putIfAbsent(documentId, Boolean.TRUE) != null) {
            throw new BusinessException(ErrorCode.OCR_FAILED,
                    "该文件正在识别中，请稍候几秒再试。");
        }
        try {
            Path file = Path.of(doc.getFilePath());
            OcrResult result;
            try {
                result = ocrEngine.recognize(file, documentId);
            } catch (OcrException ex) {
                // OCR 失败不该让文档状态变脏：它仍然是"未入库的扫描件"，
                // 用户装好依赖或换个文件后可以再来一次
                log.warn("OCR 识别失败: id={}, code={}, 原因={}",
                        documentId, ex.getErrorCode().getCode(), ex.getMessage());
                throw new BusinessException(ex.getErrorCode(), ex.getMessage());
            }

            pendingResults.put(documentId, new Pending(
                    result.text(), Instant.now(), result.engine(), result.engineVersion(),
                    result.totalPages(), result.pagesWithText(), result.imageCount(),
                    result.imagesWithText(), result.chars(), result.avgConfidence(),
                    result.elapsedMs(), result.preview()));
            log.info("OCR 识别完成（待确认）: id={}, 文件={}, {}/{} 页, 内嵌图 {}/{} 张有字, {} 字, 置信度 {}, 耗时 {} ms",
                    documentId, doc.getFileName(), result.pagesWithText(), result.totalPages(),
                    result.imagesWithText(), result.imageCount(),
                    result.chars(), result.avgConfidence(), result.elapsedMs());
            return result;
        } finally {
            inFlight.remove(documentId);
        }
    }

    /**
     * 取未过期的待确认结果；顺带清掉已过期的。
     *
     * @param documentId 文档 ID
     * @return 待确认结果；不存在或已过期时返回 {@code null}
     */
    private Pending takeValidPending(Long documentId) {
        Pending pending = pendingResults.get(documentId);
        if (pending == null) {
            return null;
        }
        if (Duration.between(pending.createdAt(), Instant.now()).compareTo(PENDING_TTL) > 0) {
            pendingResults.remove(documentId);
            return null;
        }
        return pending;
    }

    /**
     * 把待确认结果还原成识别结果对象。
     *
     * <p>为什么不直接缓存整个 {@link OcrResult}：那会同时持有全文与预览两份字符串。
     * 存原语字段、用的时候再拼装，内存占用更可控，字段也不会因为
     * "只更新了其中一份"而互相矛盾。
     *
     * @param pending 待确认结果
     * @return 识别结果
     */
    private OcrResult toResult(Pending pending) {
        return new OcrResult(
                null, pending.engine(), pending.engineVersion(),
                pending.totalPages(), pending.pagesWithText(),
                pending.imageCount(), pending.imagesWithText(),
                pending.chars(), pending.avgConfidence(), pending.elapsedMs(),
                pending.preview(), pending.text());
    }

    /**
     * 组装前端预览响应。
     *
     * @param documentId 文档 ID
     * @param fileName   文件名；传 {@code null} 表示调用方没带（自动识别路径）
     * @param result     识别结果
     * @param pending    对应的待确认结果；为 {@code null} 时由结果现算
     * @return 预览响应
     */
    private OcrPreviewResponse toPreview(Long documentId, String fileName, OcrResult result, Pending pending) {
        return new OcrPreviewResponse(
                documentId,
                fileName,
                result.totalPages(),
                result.pagesWithText(),
                result.imageCount(),
                result.imagesWithText(),
                result.chars(),
                result.avgConfidence(),
                result.elapsedMs(),
                result.lowQuality(),
                result.engine(),
                result.engineVersion(),
                pending == null ? PENDING_TTL.toSeconds()
                        : Math.max(0, PENDING_TTL.toSeconds()
                                - Duration.between(pending.createdAt(), Instant.now()).toSeconds()),
                result.preview());
    }

    /**
     * 组装前端预览响应（已有待确认结果时）。
     *
     * @param documentId 文档 ID
     * @param fileName   文件名
     * @param result     识别结果
     * @return 预览响应
     */
    private OcrPreviewResponse toPreview(Long documentId, String fileName, OcrResult result) {
        return toPreview(documentId, fileName, result, pendingResults.get(documentId));
    }

    /**
     * 解析 {@code qa.ocr.recognizable-extensions} 为小写集合。
     *
     * <p>用 {@link LinkedHashSet} 而不是 {@code Set.of}：这个顺序会经接口下发给前端，
     * 将来若要拼成提示话术（"支持 pdf、jpg…"）得稳定可读
     * （与 {@code DocumentService.validateExtension} 同一套约定）。
     *
     * <p>不缓存：这是只在"用户点了一次识别"时才走一次的路径，
     * 每次重新解析几个字符的成本可以忽略，换来的是"改了配置重启即生效"、
     * 不必再操心缓存失效。真到了热路径再谈优化。
     *
     * @return 可识别扩展名（小写，去空白）
     */
    public Set<String> recognizableExtensions() {
        String raw = properties.getOcr() == null ? null : properties.getOcr().getRecognizableExtensions();
        if (raw == null || raw.isBlank()) {
            // 兜底成 pdf：老配置文件里没有这一项时，行为与改动前保持一致，
            // 不能让"升级了 jar 但没更新 yml"直接变成功能全废。
            return Set.of("pdf");
        }
        return Arrays.stream(raw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 确认入库：把刚才识别出的文本分块、向量化并写入知识库。
     *
     * @param documentId 文档 ID
     * @return 入库的分块数
     */
    public int confirm(Long documentId) {
        // 复用同一个"取未过期结果"的入口，避免 TTL 判断在两处各写一遍 ——
        // 两份实现迟早会走偏（比如只改了其中一处的过期文案）。
        Pending pending = takeValidPending(documentId);
        if (pending == null) {
            throw new BusinessException(ErrorCode.OCR_FAILED,
                    "没有待确认的识别结果（可能已过期或服务重启过）。请重新点击「识别文字」。");
        }

        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            pendingResults.remove(documentId);
            throw new BusinessException(ErrorCode.DOC_NOT_FOUND);
        }

        try {
            documentMapper.markParsing(documentId);
            ingestService.indexText(documentId, doc, pending.text(), "OCR");
        } catch (Exception ex) {
            documentMapper.markFailed(documentId, "OCR 文本入库失败：" + ex.getMessage());
            log.error("OCR 文本入库失败: id={}", documentId, ex);
            throw new BusinessException(ErrorCode.DOC_INGEST_FAILED,
                    "OCR 文本入库失败：" + ex.getMessage());
        } finally {
            // 无论成败都要清掉：成功后再确认一次会把同一份文本重复入库
            pendingResults.remove(documentId);
        }

        // ★ 留痕：让用户在文档列表里能看出"这份文档的正文是机器识别的近似文本"。
        //   必须用 updateOriginNote 而不是 markFailed —— 后者会把状态改成 FAILED，
        //   而这份文档其实已经成功入库了（见 DocumentMapper.updateOriginNote 的说明）。
        documentMapper.updateOriginNote(documentId, describeOrigin(pending));

        Document updated = documentMapper.selectById(documentId);
        int chunks = updated == null || updated.getChunkCount() == null ? 0 : updated.getChunkCount();
        log.info("OCR 文本已确认入库: id={}, 分块 {}", documentId, chunks);
        return chunks;
    }

    /**
     * 生成入库来源描述（写进 {@code error_message} 作为留痕）。
     *
     * <p>措辞刻意直白："正文来自本机 OCR"这几个字必须在列表里看得见 ——
     * 用户有权知道检索到的是原件原文还是识别出的近似文本。
     *
     * @param pending 待确认结果
     * @return 描述文本
     */
    private String describeOrigin(Pending pending) {
        return "正文来自本机 OCR 识别（" + pending.engine() + " " + pending.engineVersion()
                + "），非原件文字层；识别结果可能有错字，请以原扫描件为准";
    }

    /**
     * 待确认的识别结果。
     *
     * <p>存原语而不是整个 {@link OcrResult}：
     * <ul>
     *   <li>{@code text} 与 {@code preview} 都留着 —— 前者用于入库，后者用于展示。
     *       只留 {@code text} 再截断出预览的话，截断规则一旦调整，
     *       "已识别的结果"和"重新识别的结果"预览长度就会不一致，很难查。</li>
     *   <li>质量指标（页数、字数、置信度）一并留着，才能让
     *       "识别完等确认"这条路径把当初那些指标原样回给用户。
     *       否则自动识别跑完之后再打开对话框，用户看到的是一份没有指标的空白结果，
     *       就无法判断"这次识别靠不靠谱"了。</li>
     * </ul>
     *
     * @param text          完整识别文本
     * @param createdAt     产生时刻（用于 TTL 判断）
     * @param engine        引擎标识
     * @param engineVersion 引擎版本
     * @param totalPages    总页数
     * @param pagesWithText 识别出文字的页数
     * @param imageCount    纳入识别的内嵌图片数
     * @param imagesWithText 读出额外文字的图片数
     * @param chars         总字数
     * @param avgConfidence 平均置信度
     * @param elapsedMs     识别耗时
     * @param preview       预览文本（识别时已截断，原样保留）
     */
    private record Pending(String text, Instant createdAt, String engine, String engineVersion,
                           int totalPages, int pagesWithText, int imageCount, int imagesWithText,
                           int chars, Double avgConfidence, long elapsedMs, String preview) {
    }
}
