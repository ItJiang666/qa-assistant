package com.itjiang.qa.model.dto;

/**
 * 一次 OCR 识别的结果。
 *
 * <p>字段分两类，用途完全不同，不要混：
 * <ul>
 *   <li><b>文本</b>（{@code text} / {@code preview}）—— 给用户看、待确认后入库</li>
 *   <li><b>质量指标</b>（{@code avgConfidence} / {@code pagesWithText} / {@code totalPages}
 *       / {@code imageCount} / {@code imagesWithText}）
 *       —— 让用户在<b>入库前</b>判断"这次识别靠不靠谱"。
 *       OCR 一定有错字，给一个平均置信度比让用户逐字核对现实得多；
 *       而 {@code pagesWithText} 明显小于 {@code totalPages} 时，
 *       往往意味着漏页（比如某几页是空白或图片质量太差），这是必须让用户看见的</li>
 * </ul>
 *
 * <h2>内嵌图片那两个统计为什么要单独暴露</h2>
 * <p>识别过程有两条路径：整页渲染（主）、逐张内嵌图（补充）。用户看到的是合并后的文本，
 * 无法分辨某段字来自哪里。而 {@code imageCount=0} 说明"这份文件里没有值得识别的插图"，
 * {@code imageCount>0 且 imagesWithText=0} 说明"有插图但里面没有文字"——
 * 这两种情况用户都不该怀疑是功能没生效。所以把统计回给界面，而不是让他猜。
 *
 * @param documentId     文档 ID
 * @param engine         引擎标识，如 {@code rapidocr}
 * @param engineVersion  引擎版本，用于事后追溯"这批文本是谁识别的"
 * @param totalPages     PDF 总页数
 * @param pagesWithText  实际识别出文字的页数
 * @param imageCount     纳入识别的内嵌图片数（已排除图标/装饰）
 * @param imagesWithText 其中读出**额外**文字的图片数。
 *                       注意"额外"：整页渲染本来就能读到插图中的文字，
 *                       那种情况下图片路径会因"内容重复"被丢弃、这个数不增加 ——
 *                       所以它为 0 <b>不代表</b>插图里没有文字，只代表没有新增内容
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
        int imageCount,
        int imagesWithText,
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
     * <p>{@code imagesWithText} 参与判据：一页里可能"几乎没有文字层，内容全在插图里"，
     * 这时 {@code pagesWithText} 天然很低，但识别其实是成功的。
     * 所以只要有插图产出文字，就不因页覆盖率低而报警 —— 否则会把正常结果
     * 误报成"疑似漏页"，而误报多了用户就不看警告了。
     *
     * @return 建议提醒用户时返回 {@code true}
     */
    public boolean lowQuality() {
        if (avgConfidence != null && avgConfidence < 0.85) {
            return true;
        }
        if (imagesWithText > 0) {
            return false;
        }
        return totalPages > 0 && pagesWithText < totalPages * 0.6;
    }
}
