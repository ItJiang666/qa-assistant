package com.itjiang.qa.model.dto;

/**
 * 一次 OCR 识别的结果。
 *
 * <p>字段分两类，用途完全不同，不要混：
 * <ul>
 *   <li><b>文本</b>（{@code text} / {@code preview}）—— 给用户看、待确认后入库</li>
 *   <li><b>质量指标</b>（{@code avgConfidence} / {@code pagesWithText} / {@code totalPages}）
 *       —— 让用户在<b>入库前</b>判断"这次识别靠不靠谱"。
 *       OCR 一定有错字，给一个平均置信度比让用户逐字核对现实得多；
 *       而 {@code pagesWithText} 明显小于 {@code totalPages} 时，
 *       往往意味着漏页（比如某几页是空白或图片质量太差），这是必须让用户看见的</li>
 * </ul>
 *
 * @param documentId     文档 ID
 * @param engine         引擎标识，如 {@code rapidocr}
 * @param engineVersion  引擎版本，用于事后追溯"这批文本是谁识别的"
 * @param totalPages     PDF 总页数
 * @param pagesWithText  实际识别出文字的页数
 * @param chars          合并后的总字数
 * @param avgConfidence  平均置信度（0~1）；无有效行时为 {@code null}
 * @param elapsedMs      识别耗时（毫秒）
 * @param preview        预览文本（截断），用于前端对话框展示
 * @param text           完整识别文本；仅在后端内部传递，接口不回给前端
 * @author Jiang
 */
public record OcrResult(
        Long documentId,
        String engine,
        String engineVersion,
        int totalPages,
        int pagesWithText,
        int chars,
        Double avgConfidence,
        long elapsedMs,
        String preview,
        String text
) {

    /**
     * 识别质量是否偏低，低到应该在界面上提醒用户"务必核对后再入库"。
     *
     * <p>判据取两个：平均置信度低于 0.85，或**有文字的页数不足总页数的 60%**
     * （后者通常意味着漏页）。这两个阈值不是拍脑袋：实测清晰印刷体扫描件的
     * 平均置信度在 0.97 以上，取 0.85 能放过正常件、拦住明显退化的；
     * 而页数覆盖率低于六成时，人工核对一次的成本低于直接入库后发现缺内容的成本。
     *
     * @return 建议提醒用户时返回 {@code true}
     */
    public boolean lowQuality() {
        if (avgConfidence != null && avgConfidence < 0.85) {
            return true;
        }
        return totalPages > 0 && pagesWithText < totalPages * 0.6;
    }
}
