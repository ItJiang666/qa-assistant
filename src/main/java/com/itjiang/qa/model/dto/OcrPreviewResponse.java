package com.itjiang.qa.model.dto;

/**
 * OCR 识别响应（**仅预览，尚未入库**）。
 *
 * <h2>为什么识别与入库要分成两次请求</h2>
 * <p>用户明确要求"人工确认后再入库"，而 OCR 一定会有错字。把两件事合成一次调用只有两种做法，
 * 都不可接受：要么识别完直接入库（用户没机会核对），要么把几万字塞进 HTTP 响应来回传
 * （大文件响应体巨大，而且文本会经过前端内存与浏览器历史）。
 *
 * <p>所以：识别结果<b>留在服务端内存</b>（带 TTL），这里只回一个预览；
 * 用户核对后调确认接口，才真正分块入库。
 *
 * @param documentId    文档 ID
 * @param fileName      文件名（前端展示"正在核对哪一份"）
 * @param totalPages    PDF 总页数
 * @param pagesWithText 实际识别出文字的页数
 * @param imageCount    纳入识别的内嵌图片数（已排除图标/装饰）
 * @param imagesWithText 其中读出**额外**文字的图片数；为 0 不代表插图里没字
 *                       （整页渲染已读到的那部分会被去重丢弃）
 * @param chars         识别出的总字数
 * @param avgConfidence 平均置信度；无有效行时为 {@code null}
 * @param elapsedMs     识别耗时
 * @param lowQuality    是否质量偏低（界面据此提示"务必核对"）
 * @param engine        引擎标识，如 {@code rapidocr}
 * @param engineVersion 引擎版本
 * @param expiresInSeconds 预览在服务端的保留时长（秒），超时需重新识别
 * @param preview       预览文本
 * @author Jiang
 */
public record OcrPreviewResponse(
        Long documentId,
        String fileName,
        int totalPages,
        int pagesWithText,
        int imageCount,
        int imagesWithText,
        int chars,
        Double avgConfidence,
        long elapsedMs,
        boolean lowQuality,
        String engine,
        String engineVersion,
        long expiresInSeconds,
        String preview
) {
}
