package com.itjiang.qa.ocr;

import com.itjiang.qa.model.dto.OcrResult;

import java.nio.file.Path;

/**
 * OCR 引擎抽象。
 *
 * <h2>为什么要有这一层接口</h2>
 * <p>OCR 是<b>可选增强</b>：项目的主流程（问答、文档入库、记忆）不允许因为它而出现
 * 新的必需依赖。所以"能不能 OCR"必须是一个可以回答的问题，而不是"调用时才发现"：
 * 界面要靠 {@link #availability()} 决定按钮是可用、置灰还是给安装指引。
 *
 * <p>同时它也是<b>换引擎的扩展点</b>。当前实现是 RapidOCR（ONNX，离线，中文质量好），
 * 若将来换成 PaddleOCR / Tesseract 或干脆换成云端 OCR，替换这一个实现即可 ——
 * 上层 {@code OcrService} 与前端都不需要改。
 *
 * @author Jiang
 */
public interface OcrEngine {

    /**
     * 引擎可用性。
     *
     * <p>刻意返回一个<b>带原因的枚举</b>而不是 {@code boolean}：
     * "不可用"至少有三种完全不同的情况（没配 Python / 没装依赖 / 主动关闭），
     * 而它们的自救方式各不相同。只回一个 false，界面就只能给一句
     * "OCR 不可用"，用户不知道该装什么。
     *
     * @return 可用性状态
     */
    Availability availability();

    /**
     * 识别一个 PDF。
     *
     * @param pdf       PDF 绝对路径
     * @param documentId 文档 ID（回填进结果，便于前端关联）
     * @return 识别结果
     * @throws OcrException 识别失败；异常里带<b>可照做</b>的提示与错误码
     */
    OcrResult recognize(Path pdf, Long documentId);

    /**
     * 引擎可用性状态。
     */
    enum Availability {
        /** 可用。 */
        READY,
        /** 配置里关掉了（{@code qa.ocr.enabled=false}）。 */
        DISABLED,
        /** 找不到 Python 解释器。 */
        NO_PYTHON,
        /** 有 Python，但没装 rapidocr / pymupdf。 */
        NO_DEPENDENCY,
        /** 脚本文件不在预期位置（多半是从非项目根目录启动的）。 */
        NO_SCRIPT;

        /**
         * 是否可以直接调用。
         *
         * @return 可用返回 {@code true}
         */
        public boolean usable() {
            return this == READY;
        }
    }
}
