package com.itjiang.qa.model.dto;

import java.time.LocalDateTime;

/**
 * 文档视图对象。
 *
 * @param id            文档 ID
 * @param fileName      原始文件名
 * @param fileType      扩展名
 * @param fileSize      字节数
 * @param chunkCount    分块数（入库完成后才有值）
 * @param status        PENDING / PARSING / INDEXED / FAILED
 * @param errorMessage  失败原因，成功时为 {@code null}；OCR 待确认时写的是提示语
 * @param ocrPending    是否有一份<b>已识别完成、等用户确认</b>的 OCR 结果在服务端内存里
 * @param embeddingModel 入库时使用的向量模型
 * @param createdAt     上传时间
 * @param updatedAt     最后状态变更时间
 * @author Jiang
 */
public record DocumentDto(
        Long id,
        String fileName,
        String fileType,
        Long fileSize,
        Integer chunkCount,
        String status,
        String errorMessage,
        boolean ocrPending,
        String embeddingModel,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
