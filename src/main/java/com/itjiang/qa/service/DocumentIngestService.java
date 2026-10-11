package com.itjiang.qa.service;

import com.itjiang.qa.common.BusinessException;
import com.itjiang.qa.config.DataDirInitializer;
import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.mapper.DocumentMapper;
import com.itjiang.qa.model.dto.OcrResult;
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
import org.springframework.context.annotation.Lazy;
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

    /**
     * 文件没有文字层时写给用户看的失败原因。
     *
     * <p><b>为什么抽成常量：</b>这句话有<b>两条</b>触发路径 ——
     * Tika 返回空文本，以及 Tika 直接抛 {@code BlankDocumentException}（实测主要是后者）。
     * 原先两处各写一份，措辞已经走偏过一次：一条说"可用「识别文字」"，
     * 另一条还停在"需要 OCR，本期不支持"——而后者才是用户实际看到的那条。
     * 同一个事实有两份文案，就一定会有一份过期。
     *
     * <p><b>措辞要点：</b>① 这两种文件（扫描版 PDF、纯图片）在用户眼里毫不相干，
     * 所以都要点名；② 必须给出<b>下一步动作</b>而不是"不支持"——
     * 界面上那列的按钮就叫「识别文字」，这三个字直接对上，用户才知道点哪儿。
     * 位置写"操作"列（表格里那列的列名），不写"右侧"：窗口窄时列会换位，
     * 方位词会变成假信息。
     */
    private static final String NO_TEXT_LAYER_MESSAGE =
            "解析结果为空。该文件可能是扫描版 PDF 或纯图片，没有可提取的文字层"
                    + "（可用「操作」列的「识别文字」在本机 OCR 后入库）";

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

    /**
     * OCR 编排（用于"解析不出文字时自动识别一次"）。
     *
     * <h2>为什么必须加 {@code @Lazy}</h2>
     * <p>{@code DocumentIngestService} 与 {@code OcrService} 是<b>双向依赖</b>：
     * <ul>
     *   <li>本类 → OcrService：解析不出文字时自动跑一次识别（本次新增）</li>
     *   <li>OcrService → 本类：用户确认后调 {@link #indexText} 把识别文本入库（原有）</li>
     * </ul>
     * 构造器注入处理不了这种环，Spring 启动会直接报
     * {@code Requested bean is currently in creation}。{@code @Lazy} 让这里注入的是
     * 一个代理，真正的解析推迟到<b>第一次调用自动识别时</b> —— 那时两个 Bean 都已建好。
     *
     * <p>更彻底的做法是把"识别"与"入库"拆成两个类，但那样要把 OCR 相关的
     * 暂存、确认、TTL 状态搬一次家，改动面远大于收益。这里留一条注释说明代价：
     * <b>环之所以安全，是因为它不在任何一次构造期闭合</b>。
     */
    private final OcrService ocrService;

    public DocumentIngestService(@Qualifier("qaEmbeddingModel") EmbeddingModel embeddingModel,
                                 VectorStoreService vectorStoreService,
                                 DocumentMapper documentMapper,
                                 QaProperties properties,
                                 DataDirInitializer dirs,
                                 RuntimeModelRegistry modelRegistry,
                                 @Lazy OcrService ocrService) {
        this.embeddingModel = embeddingModel;
        this.vectorStoreService = vectorStoreService;
        this.documentMapper = documentMapper;
        this.properties = properties;
        this.dirs = dirs;
        this.modelRegistry = modelRegistry;
        this.ocrService = ocrService;
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
                handleNoTextLayer(documentId, doc);
                return;
            }

            // ── [3][4][5][6] 分块 + 向量化 + 入库 ─────────────────────
            indexText(documentId, doc, text, "解析");

            log.info("入库完成: id={}, file={}, 耗时 {} ms",
                    documentId, doc.getFileName(), System.currentTimeMillis() - startMillis);

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
            handleNoTextLayer(documentId, doc);

        } catch (Exception ex) {
            // 兜底：任何未预期异常都要落成 FAILED，否则记录会永远卡在 PARSING，
            // 用户看到的是一个"永远在处理中"的文档，既不能用也不知道为什么
            log.error("入库失败: id={}, file={}", documentId, doc.getFileName(), ex);
            markFailed(documentId, shorten(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        }
    }

    /**
     * 处理"这份文件没有文字层"：尽量自动识别，识别不了就留下一句能照做的话。
     *
     * <h2>为什么两条路径要合并到这里</h2>
     * <p>Tika 抽不到文字有<b>两种表现</b>：返回空文本，或直接抛
     * {@code BlankDocumentException}（实测主要是后者）。原先两处各写一遍处理逻辑，
     * 措辞已经走偏过一次 —— 一条说"可用「识别文字」"，另一条还停在"需要 OCR，本期不支持"，
     * 而后者才是用户实际看到的那条。两处合成一处，就不会再有第二份过期的文案。
     *
     * <h2>自动识别失败为什么不把文档判死</h2>
     * <p>OCR 是<b>可选依赖</b>（要 Python）。没装的机器上自动识别必然失败，
     * 但那不是这份文件的问题 —— 文档仍然是"未入库的扫描件"，
     * 用户装好依赖后手动点一下就能救回来。所以失败时状态仍落 {@code FAILED}
     * 并写明原因，与"用户从没点过识别"是同一种状态，不额外制造第三种。
     *
     * <p>自动识别<b>成功</b>时状态同样是 {@code FAILED}（因为解析确实失败了），
     * 但原因文案换成"已识别出 N 字，待确认" —— 界面靠
     * {@code OcrService.hasPending()} 把这种状态显示成"待确认"而不是红色的"失败"。
     *
     * @param documentId 文档 ID
     * @param doc        文档实体
     */
    private void handleNoTextLayer(Long documentId, Document doc) {
        QaProperties.Ocr cfg = properties.getOcr();

        // 关掉自动识别，或这份文件的格式本来就不该走 OCR（docx 抽不出字是另一类问题）
        if (cfg == null || !cfg.isAutoRecognize()) {
            markFailed(documentId, NO_TEXT_LAYER_MESSAGE);
            log.warn("文档无文字层（自动识别已关闭）: id={}, file={}", documentId, doc.getFileName());
            return;
        }

        OcrResult result;
        try {
            result = ocrService.recognizeForIngest(doc);
        } catch (Exception ex) {
            // 依赖缺失 / 文件损坏 / 超时 —— 都落到"留一句能照做的话 + 保留手动按钮"
            String reason = ex instanceof BusinessException
                    ? ex.getMessage()
                    : shorten(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            markFailed(documentId, "自动识别未成功：" + reason + "。你也可以手动点「识别文字」重试。");
            log.warn("自动识别未成功: id={}, file={}, 原因={}", documentId, doc.getFileName(), reason);
            return;
        }

        // result 为 null：扩展名不在 qa.ocr.recognizable-extensions 里。
        // 注意这**不是**错误 —— 比如一份内容损坏的 docx 也会抽不出字，
        // 那时提示"可用识别文字"是误导，所以给通用文案。
        if (result == null) {
            markFailed(documentId, NO_TEXT_LAYER_MESSAGE);
            log.warn("文档无文字层，且格式不在可识别清单内: id={}, type={}, file={}",
                    documentId, doc.getFileType(), doc.getFileName());
            return;
        }

        // 识别成功但没有可用文字（纯照片、空白页）—— 给出明确结论，
        // 否则用户看到一个"失败"却不知道为什么，会反复重传同一张图
        if (result.chars() == 0) {
            markFailed(documentId, "已自动识别，但这份文件里没有读出任何文字"
                    + "（可能是纯图片、空白页或字迹过于模糊）。换一张更清晰的图可能有用。");
            log.warn("自动识别未读出文字: id={}, file={}", documentId, doc.getFileName());
            return;
        }

        // auto-confirm 才走这条分支：跳过人工核对，直接把识别文本入库。
        // 默认关闭，理由见 QaProperties.Ocr#autoConfirm（错字会静默进知识库）。
        if (cfg.isAutoConfirm()) {
            indexAutoRecognized(documentId, doc, result);
            return;
        }

        ocrService.stash(documentId, result);
        markFailed(documentId, "已自动识别出 " + result.chars() + " 字，待你确认后入库"
                + "（识别结果可能有错字，请核对后再确认）");
        log.info("自动识别完成，等待人工确认: id={}, file={}, {} 字",
                documentId, doc.getFileName(), result.chars());
    }

    /**
     * 自动识别的文本直接入库（仅当 {@code qa.ocr.auto-confirm=true}）。
     *
     * <p>留痕与手动确认完全一致（{@code OcrService.describeOrigin} 那套文案的等价物）：
     * "正文来自本机 OCR"这几个字必须在列表里看得见，否则用户会以为读到的就是原件原文。
     *
     * @param documentId 文档 ID
     * @param doc        文档实体
     * @param result     识别结果
     */
    private void indexAutoRecognized(Long documentId, Document doc, OcrResult result) {
        try {
            // 复用与手动确认同一条入库路径（同为 indexText），避免两条路各写一份
            indexText(documentId, doc, result.text(), "自动OCR");
        } catch (Exception ex) {
            // 注意：这里失败的是"入库"而不是"识别"，文案要说准 ——
            // 否则用户看到"自动识别未成功"会去换文件重传，而真正的问题是向量化那一步
            log.error("自动识别文本入库失败: id={}", documentId, ex);
            markFailed(documentId, "自动识别已完成（" + result.chars() + " 字），但入库失败："
                    + shorten(ex.getMessage()) + "。可手动点「识别文字」重试。");
            return;
        }

        documentMapper.updateOriginNote(documentId, "正文来自本机 OCR 自动识别（" + result.engine()
                + " " + result.engineVersion() + "），非原件文字层；识别结果可能有错字，请以原扫描件为准");
        log.info("自动识别文本已直接入库（auto-confirm=true）: id={}, file={}, {} 字",
                documentId, doc.getFileName(), result.chars());
    }

    /**
     * 把一段<b>已经拿到的文本</b>分块、向量化并入库。
     *
     * <p>抽出来是因为现在有<b>两个来源</b>的文本要走同一条路：
     * <ol>
     *   <li>正常路径：Tika 从文件里解析出来的文字</li>
     *   <li>OCR 路径：扫描件经本机 OCR 识别出来的文字（见 {@code OcrService}）</li>
     * </ol>
     * 两条路必须共用同一套分块、打标、向量化与落盘逻辑 ——
     * 各写一份迟早会出现"OCR 入库的文档检索不到"这类只在一条路上复现的问题。
     *
     * @param documentId 文档 ID
     * @param doc        文档实体
     * @param text       待入库文本
     * @param source     来源描述，仅用于日志（如"解析"/"OCR"）
     * @throws IllegalArgumentException 分块为空或向量数量不匹配时抛出（由调用方落成失败原因）
     */
    public void indexText(Long documentId, Document doc, String text, String source) {
        List<TextSegment> segments = split(text, doc);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("分块后没有任何有效内容");
        }

        // 批量向量化（一次调用，不要循环单条）
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        if (embeddings == null || embeddings.size() != segments.size()) {
            throw new IllegalArgumentException("向量化结果数量与分块数不一致");
        }

        vectorStoreService.add(embeddings, segments);
        documentMapper.markIndexed(documentId, segments.size());
        // 把"这些向量到底是哪个模型生成的"写回数据库：
        // 全量重建索引时会用当前模型重灌，若不同步这两列，界面与后续一致性检查都会基于旧模型名得出错误结论
        String embeddingModelName = modelRegistry.embeddingModelName();
        documentMapper.updateIndexIdentity(documentId, embeddingModelName,
                "qa_" + embeddingModelName.replaceAll("[^A-Za-z0-9]", "_"));
        vectorStoreService.persist();

        log.info("{}文本入库完成: id={}, file={}, 分块 {}", source, documentId, doc.getFileName(), segments.size());
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
