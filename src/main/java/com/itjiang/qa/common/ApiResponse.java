package com.itjiang.qa.common;

/**
 * 统一响应体（决策 18）。
 *
 * <p>所有 REST 接口一律返回本类型，前端只需一个拦截器即可处理全部错误，
 * 不必逐个接口判断 HTTP 状态码与业务错误。
 *
 * <p><b>唯一的例外是 SSE 流式接口</b>（{@code /api/v1/chat/stream}）：
 * 事件流无法套 JSON 外壳，它按 {@code text/event-stream} 协议逐条推送。
 *
 * <p>路由层虽然统一包装，但 <b>HTTP 状态码仍然正确使用</b>（400/404/500），
 * 不搞"永远返回 200"那一套 —— 那样会让网关、监控、浏览器缓存全部失去判断依据。
 *
 * @param code    业务错误码，{@code 0} 表示成功；非 0 见 {@link ErrorCode}
 * @param message 面向人的提示，可直接展示给用户
 * @param data    业务数据，无数据时为 {@code null}
 * @param <T>     业务数据类型
 * @author Jiang
 */
public record ApiResponse<T>(int code, String message, T data) {

    /**
     * 成功并携带数据。
     *
     * @param data 业务数据，允许为 {@code null}
     * @param <T>  业务数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    /**
     * 成功但无数据（如删除操作）。
     *
     * @param <T> 业务数据类型
     * @return 成功响应，{@code data} 为 {@code null}
     */
    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    /**
     * 失败，使用错误码自带的默认提示。
     *
     * @param errorCode 错误码
     * @param <T>       业务数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 失败，并覆盖默认提示。
     *
     * <p>用于把更具体的上下文告诉用户，例如"文件 X.pdf 超过 50MB"，
     * 而不是干巴巴的"文件超过大小限制"。
     *
     * @param errorCode 错误码
     * @param message   自定义提示，为空时回退到错误码默认提示
     * @param <T>       业务数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        String finalMessage = (message == null || message.isBlank()) ? errorCode.getMessage() : message;
        return new ApiResponse<>(errorCode.getCode(), finalMessage, null);
    }
}
