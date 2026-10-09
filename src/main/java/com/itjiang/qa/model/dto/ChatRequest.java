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
 * <h2>两个检索参数的取值范围是刻意收紧的</h2>
 * <p>它们看起来只是"高级选项"，但两个上限都直接关系到回答质量：
 * <ul>
 *   <li><b>{@code topK ≤ 10}</b> —— 与开发文档 §2.2 的非功能要求一致。
 *       10 块 × 500 字 ≈ 5000 字 ≈ 7.5k token，已经接近 4.6.6 定义的 8k 提示词预算；
 *       再放大就会挤掉上下文窗口，模型<b>静默丢掉一部分资料</b>（不报错，只是"看不见"）。</li>
 *   <li><b>{@code minScore ≥ 0.3}</b> —— 这个阈值是防幻觉的第一道闸门（默认 0.6）。
 *       允许调到 0 等于给了调用方一个"关闸开关"：检索结果不再做相关性过滤，
 *       噪声灌进上下文，模型会开始自相矛盾 ——
 *       实测 {@code minScore=0} 时回答是"现有资料中未找到相关信息。（注：…处理时限为 21 个工作日…）"，
 *       先说找不到、又把答案写出来。</li>
 * </ul>
 *
 * @param question       用户问题（必填）
 * @param conversationId 会话标识；为空时由服务端新建一个会话
 * @param topK           本次检索返回的片段数（1~10），为空时使用全局配置
 * @param minScore       本次检索的相似度阈值（0.3~1.0），为空时使用全局配置
 * @param remember       本次是否写入长期记忆，为空时视为 {@code true}
 * @author Jiang
 */
public record ChatRequest(

        @NotBlank(message = "问题不能为空")
        @Size(max = 2000, message = "问题长度不能超过 2000 字")
        String question,

        String conversationId,

        @Min(value = 1, message = "topK 至少为 1")
        @Max(value = 10, message = "topK 最大为 10")
        Integer topK,

        @DecimalMin(value = "0.3", message = "minScore 不能小于 0.3（低于此值检索结果将失去相关性，显著增加幻觉风险）")
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
