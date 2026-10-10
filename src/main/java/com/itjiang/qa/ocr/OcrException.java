package com.itjiang.qa.ocr;

import com.itjiang.qa.common.ErrorCode;

/**
 * OCR 相关失败。
 *
 * <p>刻意不复用通用的 {@code BusinessException}：OCR 的失败种类多，且<b>每一种的自救方式都不同</b>
 * （装 Python / 装依赖 / 拆文件 / 解密 / 重传），必须能携带一个查得到的错误码，
 * 否则界面只能显示一句"识别失败"，等于把排查成本全转给用户。
 *
 * @author Jiang
 */
public class OcrException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * @param errorCode 业务错误码
     * @param message   给用户看的提示（必须可照做）
     */
    public OcrException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * @param errorCode 业务错误码
     * @param message   给用户看的提示
     * @param cause     原始异常
     */
    public OcrException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * @return 业务错误码
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
