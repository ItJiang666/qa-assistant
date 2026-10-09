package com.itjiang.qa.model.entity;

import java.time.LocalDateTime;

/**
 * 知识库文档（表 {@code qa_document}）。
 *
 * <p>两个字段是设计上的关键，不要当成普通元数据：
 * <ul>
 *   <li>{@code embeddingModel} —— 记录了"这份文档是用哪个向量模型入库的"。
 *       换模型后向量空间会变，必须全量重建，靠这一列才能准确找出哪些文档需要重建。</li>
 *   <li>{@code collectionName} —— 所属向量集合。多模式（本地 2560 维 / 云端 1024 维）
 *       可以各自成集合，互不污染。</li>
 * </ul>
 *
 * @author Jiang
 */
public class Document {

    private Long id;

    /** 原始文件名，用于引用来源展示。 */
    private String fileName;

    /** 扩展名：pdf/docx/txt/md/xlsx/html。 */
    private String fileType;

    /** 文件字节数。 */
    private Long fileSize;

    /** 落盘绝对路径。 */
    private String filePath;

    /** 分块数，入库完成后写入。 */
    private Integer chunkCount;

    /** 状态：PENDING/PARSING/INDEXED/FAILED。 */
    private String status;

    /** 失败原因，留痕给用户看。 */
    private String errorMessage;

    /** 入库时使用的向量模型名。 */
    private String embeddingModel;

    /** 所属向量集合。 */
    private String collectionName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
