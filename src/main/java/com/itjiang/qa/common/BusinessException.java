package com.itjiang.qa.common;

/**
 * 业务异常。
 *
 * <p>用于表达"可以预期的业务失败"（文档不存在、文件类型不支持……），
 * 由 {@link GlobalExceptionHandler} 统一翻译成 {@link ApiResponse}。
 *
 * <p><b>为什么不直接抛 {@code RuntimeException}？</b>
 * 因为业务失败需要携带两样东西：一个稳定的错误码（前端据此分支处理）
 * 和一条能直接给用户看的提示。混在普通异常里，就只能靠解析 message 字符串来判断，
 * 那是维护灾难。
 *
 * <p><b>为什么不需要填堆栈？</b> 业务失败是正常流程的一部分（用户传了个不支持的文件而已），
 * 填堆栈既浪费性能，又会在日志里制造噪音，掩盖真正的故障。因此覆盖
 * {@link #fillInStackTrace()} 为空实现 —— 这是有意的取舍。
 *
 * @author Jiang
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 使用错误码自带的默认提示。
     *
     * @param errorCode 错误码
     */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null);
    }

    /**
     * 使用错误码，但覆盖提示文案。
     *
     * @param errorCode 错误码
     * @param message   更具体的提示，例如"文件 a.pdf 超过 50MB"
     */
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    /**
     * 使用错误码并保留根因，便于日志排查。
     *
     * @param errorCode 错误码
     * @param message   提示文案
     * @param cause     根因异常
     */
    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * @return 该异常对应的业务错误码
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 业务异常不采集堆栈。
     *
     * <p>理由见类注释：业务失败属于正常流程，采集堆栈只有成本没有收益。
     *
     * @return 始终返回自身
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
