package com.itjiang.qa.model.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 问答请求（开发文档 4.2.2）。
 *
 * <p>用 {@code record} 承载请求体：不可变、无 setter、序列化行为明确，
 * 也天然避免了"框架帮你 new 一个空对象"带来的隐式默认值问题。
 *
 * <p>用户标识<b>不在这里</b> —— 它来自请求头 {@code X-User-Id}，
 * 由 {@code UserContextFilter} 统一解析。把身份放进请求体，
 * 就意味着任何一个接口都可能被传错身份，而请求头只有一处解析逻辑。
 *
 * @param question       用户问题（必填）
 * @param conversationId 会话标识；为空时由服务端新建一个会话
 * @param topK           本次检索返回的片段数，为空时使用全局配置
 * @param minScore       本次检索的相似度阈值，为空时使用全局配置
 * @param remember       本次是否写入长期记忆，为空时视为 {@code true}
 * @author Jiang
 */
public record ChatRequest(

        @NotBlank(message = "问题不能为空")
        @Size(max = 2000, message = "问题长度不能超过 2000 字")
        String question,

        String conversationId,

        @Min(value = 1, message = "topK 至少为 1")
        @Max(value = 20, message = "topK 最大为 20")
        Integer topK,

        @DecimalMin(value = "0.0", message = "minScore 不能小于 0")
        @DecimalMax(value = "1.0", message = "minScore 不能大于 1")
        Double minScore,

        Boolean remember
) {

    /**
     * 是否允许写入长期记忆。
     *
     * <p>默认允许：长期记忆的价值在于"润物细无声"，每次都要用户手动勾选就没人用了。
     * 但对隐私敏感的用户必须留出关闭入口（决策 7）。
     *
     * @return 未显式关闭时返回 {@code true}
     */
    public boolean rememberOrDefault() {
        return remember == null || remember;
    }
}
