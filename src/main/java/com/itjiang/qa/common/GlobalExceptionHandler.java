package com.itjiang.qa.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理（决策 18）。
 *
 * <p>把三类失败统一翻译成 {@link ApiResponse}，保证前端拿到永远是同一种结构：
 * <ol>
 *   <li><b>业务失败</b> —— {@link BusinessException}，按错误码映射 HTTP 状态，日志用 WARN</li>
 *   <li><b>参数问题</b> —— 校验失败、缺参数、方法不支持、文件超限等，返回 4xx，日志用 WARN</li>
 *   <li><b>未预期异常</b> —— 一律 500，<b>日志打全堆栈但只给用户一句通用提示</b>，
 *       绝不把内部异常信息（可能含路径、SQL、密钥）回显到响应里</li>
 * </ol>
 *
 * @author Jiang
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常：可预期，按错误码返回对应 HTTP 状态。
     *
     * @param ex 业务异常
     * @return 统一失败响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        // 业务失败是正常流程的一部分，用 WARN 且不打堆栈，避免噪音掩盖真故障
        log.warn("业务异常 code={} message={}", errorCode.getCode(), ex.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.fail(errorCode, ex.getMessage()));
    }

    /**
     * {@code @Valid} 校验失败（请求体参数）。
     *
     * @param ex 校验异常
     * @return 400 + 聚合后的字段提示
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, detail));
    }

    /**
     * {@code @Validated} 校验失败（方法级参数，如 {@code @RequestParam}）。
     *
     * @param ex 约束违反异常
     * @return 400 + 聚合后的提示
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        String detail = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, detail));
    }

    /**
     * 缺少必填的请求参数。
     *
     * @param ex 缺参异常
     * @return 400
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        String detail = "缺少必填参数: " + ex.getParameterName();
        log.warn(detail);
        return ResponseEntity.status(ErrorCode.PARAM_INVALID.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, detail));
    }

    /**
     * 上传文件超过 {@code spring.servlet.multipart.max-file-size}。
     *
     * <p>这个异常发生在请求体解析阶段，进不了业务代码，只能在这里兜住 ——
     * 否则用户看到的是一个没有解释的连接中断。
     *
     * @param ex 超限异常
     * @return 413
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("上传文件超过大小限制: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.DOC_TOO_LARGE.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.DOC_TOO_LARGE));
    }

    /**
     * 请求方法不支持（例如对只收 POST 的路径发了 GET）。
     *
     * @param ex 方法不支持异常
     * @return 405
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("请求方法不支持: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.METHOD_NOT_ALLOWED));
    }

    /**
     * 静态资源 / 路径不存在。
     *
     * <p>交给 Spring 自己处理（返回 404），不要让下面的兜底分支把它记成"系统错误"，
     * 否则任何一次打错地址都会在日志里留下一条 ERROR。
     *
     * @param ex 资源不存在异常
     * @return 404
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(NoResourceFoundException ex) {
        log.debug("资源不存在: {}", ex.getResourcePath());
        return ResponseEntity.status(404)
                .body(ApiResponse.fail(ErrorCode.PARAM_INVALID, "接口或资源不存在: " + ex.getResourcePath()));
    }

    /**
     * 兜底：未预期的异常。
     *
     * <p><b>注意这里的信息边界</b>：堆栈只进日志，响应里只给一句通用提示。
     * 直接把 {@code ex.getMessage()} 回显出去，可能泄露绝对路径、SQL 片段甚至连接串。
     *
     * @param ex 未预期异常
     * @return 500 + 通用提示
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception ex) {
        log.error("未预期的系统异常", ex);
        return ResponseEntity.status(ErrorCode.SYSTEM_ERROR.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.SYSTEM_ERROR));
    }
}
