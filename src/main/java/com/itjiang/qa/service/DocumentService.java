package com.itjiang.qa.service;

import com.itjiang.qa.common.BusinessException;
import com.itjiang.qa.common.ErrorCode;
import com.itjiang.qa.config.DataDirInitializer;
import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.mapper.DocumentMapper;
import com.itjiang.qa.model.dto.DocumentDto;
import com.itjiang.qa.model.dto.UploadResult;
import com.itjiang.qa.model.entity.Document;
import com.itjiang.qa.model.enums.DocStatus;
import com.itjiang.qa.provider.RuntimeModelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文档管理（开发文档 4.2.1）。
 *
 * <p>负责"接收文件 → 落盘 → 写元数据 → 触发异步入库"这一段，
 * 以及列表 / 详情 / 删除 / 全量重建。
 *
 * <h2>安全要点：绝不使用客户端提供的文件名作为落盘路径</h2>
 * <p>传入的 {@code fileName} 可能是 {@code ../../../../etc/passwd} 这类<b>路径穿越</b>攻击串，
 * 也可能是 Windows 保留名（{@code CON}、{@code NUL}）或超长名字。
 * 这里的做法是：<b>落盘名一律用服务端生成的 UUID</b>，
 * 原始文件名只作为元数据存库、只用于展示。这样路径穿越在原理上就不可能发生。
 *
 * @author Jiang
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** 日期分目录格式：uploads/20261009/uuid.pdf。 */
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 文件名元数据列宽 VARCHAR(255)，超长要截断，否则插入失败。 */
    private static final int FILE_NAME_MAX_LENGTH = 255;

    private final DocumentMapper documentMapper;
    private final DocumentIngestService ingestService;
    private final VectorStoreService vectorStoreService;
    private final QaProperties properties;
    private final DataDirInitializer dirs;
    private final RuntimeModelRegistry modelRegistry;

    /**
     * 只为查询"这份文档有没有待确认的识别结果"。
     *
     * <p>这份状态存在 {@code OcrService} 的内存 Map 里，不落库；而列表接口需要它
     * 才能把"已识别完、等确认"的行显示成待确认而不是红色的"失败"。
     * 依赖方向是单向的（DocumentService → OcrService），不构成环。
     */
    private final OcrService ocrService;

    public DocumentService(DocumentMapper documentMapper,
                           DocumentIngestService ingestService,
                           VectorStoreService vectorStoreService,
                           QaProperties properties,
                           DataDirInitializer dirs,
                           RuntimeModelRegistry modelRegistry,
                           OcrService ocrService) {
        this.documentMapper = documentMapper;
        this.ingestService = ingestService;
        this.vectorStoreService = vectorStoreService;
        this.properties = properties;
        this.dirs = dirs;
        this.modelRegistry = modelRegistry;
        this.ocrService = ocrService;
    }

    /**
     * 接收并保存一个上传文件，随后异步入库。
     *
     * @param file 上传的文件
     * @return 受理结果（此时尚未入库完成，状态为 PENDING）
     */
    public UploadResult upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "上传文件为空");
        }

        String originalName = sanitizeFileName(file.getOriginalFilename());
        String extension = extractExtension(originalName);
        validateExtension(extension);

        Path stored = storeFile(file, extension);

        Document document = new Document();
        document.setFileName(originalName);
        document.setFileType(extension);
        document.setFileSize(file.getSize());
        document.setFilePath(stored.toString());
        document.setStatus(DocStatus.PENDING.getValue());
        document.setChunkCount(0);
        document.setEmbeddingModel(modelRegistry.embeddingModelName());
        document.setCollectionName(collectionName());
        documentMapper.insert(document);

        // 立即返回、后台处理。同步做的话，一个大文件会让上传接口直接超时
        ingestService.ingestAsync(document.getId());

        log.info("已受理上传: id={}, file={}, size={} B", document.getId(), originalName, file.getSize());
        return new UploadResult(document.getId(), originalName, file.getSize(),
                DocStatus.PENDING.getValue());
    }

    /**
     * 分页查询文档列表。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return 文档列表（最新在前）
     */
    public List<DocumentDto> list(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 1);
        return documentMapper.selectPage(safeSize, (safePage - 1) * safeSize)
                .stream().map(this::toDto).toList();
    }

    /**
     * 文档总数（供分页展示）。
     *
     * @return 总数
     */
    public long count() {
        return documentMapper.countAll();
    }

    /**
     * 查询单个文档详情。
     *
     * @param id 文档 ID
     * @return 文档详情
     */
    public DocumentDto detail(Long id) {
        return toDto(requireDocument(id));
    }

    /**
     * 删除文档：向量、文件、记录三者都要清掉。
     *
     * <p>顺序很重要：<b>先删向量，再删文件，最后删数据库记录</b>。
     * 反过来的话，一旦中间失败，就会留下"记录没了但向量还在"的孤儿向量 ——
     * 那种数据检索得到、却查不到来源，用户看到的是"引用了不存在的文档"。
     *
     * @param id 文档 ID
     */
    public void delete(Long id) {
        Document document = requireDocument(id);

        vectorStoreService.removeByDocumentId(id);
        vectorStoreService.persist();
        deleteFileQuietly(document.getFilePath());
        documentMapper.deleteById(id);

        log.info("已删除文档: id={}, file={}", id, document.getFileName());
    }

    /**
     * 全量重建索引（F10）。
     *
     * <p>换 embedding 模型后<b>必须</b>执行：新旧向量处于不同向量空间，
     * 混在一起不会报错，只会让检索结果莫名其妙地变差。
     *
     * <p>做法是清空向量库后逐份重新入库。清空是必要的 ——
     * 否则旧向量会一直留在库里被检索到。
     *
     * @return 触发重建的文档数
     */
    public int reindex() {
        List<Document> all = documentMapper.selectAll();
        vectorStoreService.clear();
        for (Document document : all) {
            ingestService.ingestAsync(document.getId());
        }
        log.warn("已触发全量重建索引: {} 份文档", all.size());
        return all.size();
    }

    /**
     * 当前生效的向量模型名（界面与重建索引提示用）。
     *
     * <p>P4 起必须问注册表：云端模式下向量模型不再是 {@code qa.local.embedding-model}，
     * 继续读本地配置会让"重建索引"这类操作提示出错误的模型名。
     *
     * @return 当前向量模型名
     */
    public String currentEmbeddingModel() {
        return modelRegistry.embeddingModelName();
    }

    /**
     * 落盘。
     *
     * @param file      上传文件
     * @param extension 扩展名
     * @return 落盘后的绝对路径
     */
    private Path storeFile(MultipartFile file, String extension) {
        String day = LocalDate.now().format(DAY_FORMAT);
        Path dayDir = dirs.uploadDir().resolve(day);
        try {
            Files.createDirectories(dayDir);
            // ★ 落盘名用 UUID，与客户端提供的文件名彻底解耦（防路径穿越）
            Path target = dayDir.resolve(UUID.randomUUID() + "." + extension);
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target.toAbsolutePath().normalize();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件保存失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 删文件，失败只记日志。
     *
     * <p>文件可能已被用户手工删掉。此时删除流程仍应继续 ——
     * 为了一个已经不存在的文件而拒绝删除记录，只会让用户卡在"删不掉"的状态。
     *
     * @param filePath 文件路径
     */
    private void deleteFileQuietly(String filePath) {
        if (filePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(Path.of(filePath));
        } catch (IOException ex) {
            log.warn("删除文件失败（忽略，继续删除记录）: {}: {}", filePath, ex.getMessage());
        }
    }

    /**
     * 取文档或抛业务异常。
     *
     * @param id 文档 ID
     * @return 文档实体
     */
    private Document requireDocument(Long id) {
        Document document = documentMapper.selectById(id);
        if (document == null) {
            throw new BusinessException(ErrorCode.DOC_NOT_FOUND);
        }
        return document;
    }

    /**
     * 校验扩展名白名单。
     *
     * <p><b>为什么用 LinkedHashSet 而不是 HashSet</b>：这条报错要把支持格式念给用户听，
     * 而 HashSet 的迭代顺序是按哈希排的。实测同一份配置在界面上显示成
     * {@code txt / xlsx / pdf / md / html / docx} —— 既不是配置顺序也不是字母序，
     * 用户第一眼很容易读成"不支持 docx、pdf"。LinkedHashSet 保持配置里的书写顺序，
     * 念出来才像人话。
     *
     * <p>措辞也改过：原来是「允许: A / B / C」，斜杠分隔的一长串很难看出
     * 哪个是"不支持的"哪个是"支持的"。现在把不支持的单独放前面，支持的后置且用顿号。
     *
     * @param extension 小写扩展名
     */
    private void validateExtension(String extension) {
        Set<String> allowed = Arrays.stream(properties.getAllowedExtensions().split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!allowed.contains(extension)) {
            throw new BusinessException(ErrorCode.DOC_TYPE_NOT_ALLOWED,
                    "不支持 ." + extension + " 格式；本系统支持：" + String.join("、", allowed));
        }
    }

    /**
     * 清理客户端文件名。
     *
     * <p>只取路径最后一段（去掉 {@code ../} 之类），并截断到列宽以内。
     * 注意：这只是"让展示值干净"，<b>真正的安全保证来自落盘用 UUID</b>。
     *
     * @param raw 原始文件名
     * @return 可用于展示和入库的文件名
     */
    private String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unnamed";
        }
        String normalized = raw.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) {
            return "unnamed";
        }
        return name.length() <= FILE_NAME_MAX_LENGTH ? name : name.substring(0, FILE_NAME_MAX_LENGTH);
    }

    /**
     * 取小写扩展名。
     *
     * @param fileName 文件名
     * @return 扩展名；没有扩展名时返回空串
     */
    private String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 向量集合名。
     *
     * <p>把向量模型名编进集合名，不同模型入库的数据天然隔离 ——
     * 将来支持多模型共存时不必再改表结构。
     *
     * @return 集合名
     */
    private String collectionName() {
        return "qa_" + modelRegistry.embeddingModelName().replaceAll("[^A-Za-z0-9]", "_");
    }

    /**
     * 实体 → DTO。
     *
     * <p>{@code ocrPending} 必须在这里现查一次（内存 Map），而不是靠数据库列：
     * 待确认结果是<b>一次性的中间态</b>，用户不确认就该消失（见 {@code OcrService}），
     * 落库反而要额外考虑清理。代价是列表接口每行查一次 Map ——
     * 这是 O(1) 的内存查询，相对于同一次请求里的分页 SQL 可以忽略。
     *
     * @param document 文档实体
     * @return 文档视图对象
     */
    private DocumentDto toDto(Document document) {
        return new DocumentDto(
                document.getId(),
                document.getFileName(),
                document.getFileType(),
                document.getFileSize(),
                document.getChunkCount(),
                document.getStatus(),
                document.getErrorMessage(),
                ocrService.hasPending(document.getId()),
                document.getEmbeddingModel(),
                document.getCreatedAt(),
                document.getUpdatedAt());
    }
}
