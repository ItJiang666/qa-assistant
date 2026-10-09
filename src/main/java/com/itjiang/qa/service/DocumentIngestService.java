package com.itjiang.qa.service;

import com.itjiang.qa.config.DataDirInitializer;
import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.mapper.DocumentMapper;
import com.itjiang.qa.model.entity.Document;
import com.itjiang.qa.model.enums.DocStatus;
import com.itjiang.qa.provider.RuntimeModelRegistry;
import com.itjiang.qa.rag.ChineseDocumentSplitters;
import com.itjiang.qa.rag.SegmentMetadata;
import dev.langchain4j.data.document.BlankDocumentException;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档入库流水线（开发文档 4.3.1）。
 *
 * <p>六步：<b>解析 → 空文本检查 → 分块 → 打标 → 批量向量化 → 入库</b>。
 *
 * <h2>三个容易做错的地方</h2>
 * <ol>
 *   <li><b>必须异步。</b> 一个 50MB 的 PDF 解析加向量化要几十秒，
 *       同步执行会让上传接口超时。接口立即返回 PENDING，前端轮询状态。</li>
 *   <li><b>必须批量向量化。</b> 用 {@code embedAll} 一次传一批，
 *       比循环单条调快数倍 —— 每次调用都是一次 HTTP 往返。</li>
 *   <li><b>必须打 metadata 标。</b> 检索结果要能回溯到"来自哪个文档的哪一块"，
 *       这是引用来源功能的基础；也是删除文档时按 documentId 过滤向量的前提。</li>
 * </ol>
 *
 * <h2>失败留痕</h2>
 * <p>任何一步失败都写入 {@code status=FAILED} + {@code error_message}。
 * 尤其是"解析结果为空"——那几乎一定是扫描版 PDF（没有文字层），
 * 明确告诉用户这一点，比让他反复重传有用得多。这也是开发文档第 9 章
 * 「何时拆分 Python」决策树里的第一个判断点。
 *
 * @author Jiang
 */
@Service
public class DocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestService.class);

    /** metadata 键：文档 ID。删除向量时按它过滤，不能改名。 */
    public static final String META_DOCUMENT_ID = SegmentMetadata.DOCUMENT_ID;

    /** metadata 键：原始文件名。引用来源展示用。 */
    public static final String META_FILE_NAME = SegmentMetadata.FILE_NAME;

    /** metadata 键：分块序号，从 0 开始。 */
    public static final String META_CHUNK_INDEX = SegmentMetadata.CHUNK_INDEX;

    private final EmbeddingModel embeddingModel;
    private final VectorStoreService vectorStoreService;
    private final DocumentMapper documentMapper;
    private final QaProperties properties;
    private final DataDirInitializer dirs;
    private final RuntimeModelRegistry modelRegistry;

    public DocumentIngestService(@Qualifier("qaEmbeddingModel") EmbeddingModel embeddingModel,
                                 VectorStoreService vectorStoreService,
                                 DocumentMapper documentMapper,
                                 QaProperties properties,
                                 DataDirInitializer dirs,
                                 RuntimeModelRegistry modelRegistry) {
        this.embeddingModel = embeddingModel;
        this.vectorStoreService = vectorStoreService;
        this.documentMapper = documentMapper;
        this.properties = properties;
        this.dirs = dirs;
        this.modelRegistry = modelRegistry;
    }

    /**
     * 异步执行入库。
     *
     * <p>方法签名只收 {@code documentId} 而不是整个实体：异步任务可能在几百毫秒后才真正开始，
     * 那时传入的对象已经过时；重新从库里查一次才能保证用的是最新状态。
     *
     * @param documentId 文档 ID
     */
    @Async("ingestExecutor")
    public void ingestAsync(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            log.warn("入库任务忽略：文档 {} 已不存在", documentId);
            return;
        }

        long startMillis = System.currentTimeMillis();
        log.info("开始入库: id={}, file={}", documentId, doc.getFileName());

        try {
            documentMapper.markParsing(documentId);

            // ── [1] 解析 ──────────────────────────────────────────────
            Path file = Path.of(doc.getFilePath());
            String text = FileSystemDocumentLoader
                    .loadDocument(file, new ApacheTikaDocumentParser())
                    .text();

            // ── [2] 空文本检查 ────────────────────────────────────────
            if (text == null || text.isBlank()) {
                markFailed(documentId, "解析结果为空。该文件可能是扫描版 PDF 或纯图片，"
                        + "没有可提取的文字层（需要 OCR，本期不支持）");
                log.warn("解析结果为空: id={}, file={}", documentId, doc.getFileName());
                return;
            }

            // ── [3][4] 分块 + 打标 ────────────────────────────────────
            List<TextSegment> segments = split(text, doc);
            if (segments.isEmpty()) {
                markFailed(documentId, "分块后没有任何有效内容");
                return;
            }

            // ── [5] 批量向量化（一次调用，不要循环单条）──────────────
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            if (embeddings == null || embeddings.size() != segments.size()) {
                markFailed(documentId, "向量化结果数量与分块数不一致");
                return;
            }

            // ── [6] 入库 + 落盘 ───────────────────────────────────────
            vectorStoreService.add(embeddings, segments);
            documentMapper.markIndexed(documentId, segments.size());
            // 把"这些向量到底是哪个模型生成的"写回数据库：
            // 全量重建索引时会用当前模型重灌，若不同步这两列，界面与后续一致性检查都会基于旧模型名得出错误结论
            String embeddingModelName = modelRegistry.embeddingModelName();
            documentMapper.updateIndexIdentity(documentId, embeddingModelName,
                    "qa_" + embeddingModelName.replaceAll("[^A-Za-z0-9]", "_"));
            vectorStoreService.persist();

            log.info("入库完成: id={}, file={}, 分块 {}，耗时 {} ms",
                    documentId, doc.getFileName(), segments.size(),
                    System.currentTimeMillis() - startMillis);

        } catch (BlankDocumentException ex) {
            // ★ 扫描版 PDF / 纯图片文档走的是这条路径，而不是上面的空值检查。
            //
            //   原因：FileSystemDocumentLoader.loadDocument(...) 对"解析不出任何文字"的文档
            //   是【抛异常】而不是返回一个 text 为空的 Document。所以 if (text.isBlank())
            //   那段代码对最重要的失败场景根本不会执行 —— 实测扫描件上传后，
            //   用户看到的是 "BlankDocumentException: The document is blank" 这种
            //   技术黑话，完全不知道该怎么办。
            //
            //   这正是开发文档第 9 章「何时拆分 Python」决策树的第一个判断点，
            //   必须给出人能看懂的结论。
            markFailed(documentId, "解析结果为空。该文件可能是扫描版 PDF 或纯图片，"
                    + "没有可提取的文字层（需要 OCR，本期不支持）");
            log.warn("文档无文字层（疑似扫描件）: id={}, file={}", documentId, doc.getFileName());

        } catch (Exception ex) {
            // 兜底：任何未预期异常都要落成 FAILED，否则记录会永远卡在 PARSING，
            // 用户看到的是一个"永远在处理中"的文档，既不能用也不知道为什么
            log.error("入库失败: id={}, file={}", documentId, doc.getFileName(), ex);
            markFailed(documentId, shorten(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
    }

    /**
     * 分块并给每块打标。
     *
     * @param text 文档全文
     * @param doc  文档实体（提供 ID 与文件名用于打标）
     * @return 文本片段列表
     */
    private List<TextSegment> split(String text, Document doc) {
        int chunkSize = properties.getIngest().getChunkSize();
        int overlap = properties.getIngest().getChunkOverlap();

        // 用中文友好的分块器，而不是 LangChain4j 自带的 DocumentSplitters.recursive：
        // 后者的句子层是 OpenNLP 英文模型、词层按空格切分，对中文双双失效，
        // 长中文段落会被退化成按字符硬切、从句子中间断开（详见 ChineseDocumentSplitters）
        DocumentSplitter splitter = ChineseDocumentSplitters.recursive(chunkSize, overlap);
        List<TextSegment> raw = splitter.split(
                dev.langchain4j.data.document.Document.from(text));

        List<TextSegment> result = new ArrayList<>(raw.size());
        int index = 0;
        for (TextSegment segment : raw) {
            if (segment.text() == null || segment.text().isBlank()) {
                continue;
            }
            Metadata metadata = new Metadata()
                    .put(META_DOCUMENT_ID, doc.getId())
                    .put(META_FILE_NAME, doc.getFileName())
                    .put(META_CHUNK_INDEX, index++);
            result.add(TextSegment.from(segment.text(), metadata));
        }
        return result;
    }

    /**
     * 标记失败并记录原因。
     *
     * @param documentId 文档 ID
     * @param reason     失败原因（会被截断到列宽以内）
     */
    private void markFailed(Long documentId, String reason) {
        try {
            documentMapper.markFailed(documentId, shorten(reason));
        } catch (Exception ex) {
            log.error("写入失败状态时又出错: id={}", documentId, ex);
        }
    }

    /**
     * 截断错误消息。
     *
     * <p>{@code error_message} 列宽 1000。超长的堆栈消息会让插入直接失败 ——
     * 那样连"失败原因"都留不下来，是最糟的结果。
     *
     * @param message 原始消息
     * @return 截断后的消息
     */
    private String shorten(String message) {
        if (message == null) {
            return "未知错误";
        }
        return message.length() <= 900 ? message : message.substring(0, 900) + "…";
    }
}
