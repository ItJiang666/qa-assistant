package com.itjiang.qa.model.dto;

/**
 * 上传受理结果。
 *
 * <p>注意状态是 {@code PENDING} 而<b>不是</b> {@code INDEXED} ——
 * 上传接口只负责"收下并开始处理"，解析和向量化在后台进行。
 * 前端拿到这个响应后应轮询 {@code GET /api/v1/documents/{id}} 看状态变化。
 *
 * <p>把这一点如实返回，比假装"上传成功即完成"要好：用户能看到进度，
 * 也能在失败时看到原因（扫描版 PDF 之类）。
 *
 * @param id       文档 ID
 * @param fileName 原始文件名
 * @param fileSize 字节数
 * @param status   当前状态，应为 {@code PENDING}
 * @author Jiang
 */
public record UploadResult(
        Long id,
        String fileName,
        Long fileSize,
        String status
) {
}
