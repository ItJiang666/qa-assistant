package com.itjiang.qa.common;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码（决策 18）。
 *
 * <p><b>分段规则</b>（新增错误码必须落在对应区段，便于一眼定位来源模块）：
 * <pre>
 *   0      成功
 *   1xxx   文档模块
 *   2xxx   问答模块
 *   3xxx   记忆模块
 *   4xxx   通用参数与请求
 *   9xxx   系统与外部依赖
 * </pre>
 *
 * <p>每个错误码同时携带一个默认 {@link HttpStatus}：业务上"没找到"就该返回 404，
 * "参数不合法"就该返回 400，而不是一律 200/500。这样网关、监控、前端缓存都能正常工作。
 *
 * @author Jiang
 */
public enum ErrorCode {

    // ── 成功 ────────────────────────────────────────────────────────────────
    /** 成功。 */
    SUCCESS(0, "ok", HttpStatus.OK),

    // ── 1xxx 文档 ───────────────────────────────────────────────────────────
    /** 文档不存在。 */
    DOC_NOT_FOUND(1001, "文档不存在", HttpStatus.NOT_FOUND),
    /** 文件类型不在白名单内。 */
    DOC_TYPE_NOT_ALLOWED(1002, "不支持的文件类型", HttpStatus.BAD_REQUEST),
    /** 文件超过大小上限。 */
    DOC_TOO_LARGE(1003, "文件超过大小限制", HttpStatus.PAYLOAD_TOO_LARGE),
    /** 解析结果为空文本。 */
    DOC_PARSE_EMPTY(1004, "解析结果为空，可能是扫描版 PDF（需 OCR，本期不支持）", HttpStatus.UNPROCESSABLE_ENTITY),
    /** 入库流水线失败。 */
    DOC_INGEST_FAILED(1005, "文档入库失败", HttpStatus.INTERNAL_SERVER_ERROR),
    /**
     * 向量索引与当前向量模型不匹配。
     *
     * <p>单独立一个码而不是复用"参数不合法"：前端要据此在界面上给出「重建索引」入口，
     * 而参数错误是用户改输入就能解决的，两者处置方式完全不同。
     * HTTP 用 422：请求本身没错，是服务端的数据状态与当前配置不一致。
     */
    INDEX_MODEL_MISMATCH(1006, "向量索引与当前向量模型不匹配，请重建索引", HttpStatus.UNPROCESSABLE_ENTITY),

    // ── 2xxx 问答 ───────────────────────────────────────────────────────────
    /** 问题为空。 */
    CHAT_QUESTION_BLANK(2001, "问题不能为空", HttpStatus.BAD_REQUEST),
    /** 调用对话模型失败。 */
    CHAT_MODEL_ERROR(2002, "模型调用失败", HttpStatus.BAD_GATEWAY),
    /** 会话不存在。 */
    CONVERSATION_NOT_FOUND(2003, "会话不存在", HttpStatus.NOT_FOUND),

    // ── 3xxx 记忆 ───────────────────────────────────────────────────────────
    /** 记忆不存在。 */
    MEMORY_NOT_FOUND(3001, "记忆不存在", HttpStatus.NOT_FOUND),
    /** 记忆抽取失败（不阻塞主流程，仅记录）。 */
    MEMORY_EXTRACT_FAILED(3002, "记忆抽取失败", HttpStatus.INTERNAL_SERVER_ERROR),

    // ── 4xxx 通用参数 ───────────────────────────────────────────────────────
    /** 参数校验不通过。 */
    PARAM_INVALID(4000, "参数不合法", HttpStatus.BAD_REQUEST),
    /** 请求方法不支持。 */
    METHOD_NOT_ALLOWED(4001, "请求方法不支持", HttpStatus.METHOD_NOT_ALLOWED),

    // ── 9xxx 系统与外部依赖 ─────────────────────────────────────────────────
    /** 未预期的系统异常。 */
    SYSTEM_ERROR(9000, "系统内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    /**
     * 外部依赖不可用，最常见的是 Ollama 没启动。
     *
     * <p>单独给一个错误码，是因为这个场景太常见了 —— 明确告诉用户"Ollama 连不上"，
     * 比抛一个 {@code ConnectException} 堆栈友好得多。
     */
    UPSTREAM_UNAVAILABLE(9001, "依赖服务不可用（本地模式请确认 Ollama 已启动）", HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    /**
     * @return 业务错误码
     */
    public int getCode() {
        return code;
    }

    /**
     * @return 面向用户的默认提示
     */
    public String getMessage() {
        return message;
    }

    /**
     * @return 该错误码对应的 HTTP 状态码
     */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
