package com.itjiang.qa.service;

import com.itjiang.qa.common.BusinessException;
import com.itjiang.qa.common.ErrorCode;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * 待确认的识别结果：documentId → (文本 + 元信息 + 产生时刻)。
     *
     * <p>用内存而不是落库：这是<b>一次性的中间态</b>，用户不确认就该消失。
     * 落库还要考虑清理，而内存 Map 天然随进程消失 —— 重启后要求重新识别是合理行为，
     * 因为"未确认过的东西"本就不该被持久化。
     */
    private final Map<Long, Pending> pendingResults = new ConcurrentHashMap<>();

    public OcrService(DocumentMapper documentMapper,
                      DocumentIngestService ingestService,
                      OcrEngine ocrEngine) {
        this.documentMapper = documentMapper;
        this.ingestService = ingestService;
        this.ocrEngine = ocrEngine;
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
     * @param documentId 文档 ID
     * @return 识别预览
     */
    public OcrPreviewResponse recognize(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw new BusinessException(ErrorCode.DOC_NOT_FOUND);
        }

        // 只对 PDF 有意义：OCR 的价值在于"从图像里读出文字"，
        // docx/txt 本来就带文字层，走普通解析路径即可，没必要绕一圈 OCR。
        String type = doc.getFileType() == null ? "" : doc.getFileType().toLowerCase();
        if (!"pdf".equals(type)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "「识别文字」只对 PDF 有效（当前文件类型：" + type + "）。"
                            + "其它格式本来就带文字层，直接上传即可被解析。");
        }

        Path file = Path.of(doc.getFilePath());
        if (!Files.isRegularFile(file)) {
            throw new BusinessException(ErrorCode.DOC_NOT_FOUND,
                    "文件已不在磁盘上（" + doc.getFilePath() + "），请重新上传。");
        }

        OcrResult result;
        try {
            result = ocrEngine.recognize(file, documentId);
        } catch (OcrException ex) {
            // OCR 失败不该让文档状态变脏：它仍然是"未入库的扫描件"，
            // 用户装好依赖或换个文件后可以再来一次
            log.warn("OCR 识别失败: id={}, code={}, 原因={}", documentId, ex.getErrorCode().getCode(), ex.getMessage());
            throw new BusinessException(ex.getErrorCode(), ex.getMessage());
        }

        pendingResults.put(documentId, new Pending(result.text(), Instant.now(), result.engine(), result.engineVersion()));
        log.info("OCR 识别完成（待确认）: id={}, 文件={}, {}/{} 页, 内嵌图 {}/{} 张有字, {} 字, 置信度 {}, 耗时 {} ms",
                documentId, doc.getFileName(), result.pagesWithText(), result.totalPages(),
                result.imagesWithText(), result.imageCount(),
                result.chars(), result.avgConfidence(), result.elapsedMs());

        return new OcrPreviewResponse(
                documentId,
                doc.getFileName(),
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
                PENDING_TTL.toSeconds(),
                result.preview());
    }

    /**
     * 确认入库：把刚才识别出的文本分块、向量化并写入知识库。
     *
     * @param documentId 文档 ID
     * @return 入库的分块数
     */
    public int confirm(Long documentId) {
        Pending pending = pendingResults.get(documentId);
        if (pending == null) {
            throw new BusinessException(ErrorCode.OCR_FAILED,
                    "没有待确认的识别结果（可能已过期或服务重启过）。请重新点击「识别文字」。");
        }
        if (Duration.between(pending.createdAt(), Instant.now()).compareTo(PENDING_TTL) > 0) {
            pendingResults.remove(documentId);
            throw new BusinessException(ErrorCode.OCR_FAILED,
                    "识别结果已超过 " + PENDING_TTL.toMinutes() + " 分钟，已失效。请重新点击「识别文字」。");
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
     * @param text          完整识别文本
     * @param createdAt     产生时刻（用于 TTL 判断）
     * @param engine        引擎标识
     * @param engineVersion 引擎版本
     */
    private record Pending(String text, Instant createdAt, String engine, String engineVersion) {
    }
}
