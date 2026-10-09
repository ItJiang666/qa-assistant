package com.itjiang.qa.model.dto;

import jakarta.validation.Validator;
import jakarta.validation.Validation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChatRequest} 参数约束测试。
 *
 * <p>为什么这些约束值得专门测试：{@code topK} 与 {@code minScore} 看起来只是"高级选项"，
 * 但两个上界都直接决定回答质量 ——
 * {@code topK} 过大会把上下文窗口挤爆（模型静默丢资料），
 * {@code minScore} 过小会让噪声进上下文（模型开始自相矛盾）。
 *
 * <p>用纯 {@link Validator} 而不是发 HTTP 请求：这层校验发生在参数绑定阶段，
 * 启动整个应用再打请求既慢又引入无关变量（端口、模型、知识库内容）。
 * 单元测试能精确锁定"哪个值该过、哪个值该拦"，跑一次只要几十毫秒。
 *
 * @author Jiang
 */
class ChatRequestValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    /**
     * 构造一个只关心指定字段的请求。
     *
     * @param question 问题
     * @param topK     检索条数
     * @param minScore 相似度阈值
     * @return 请求对象
     */
    private ChatRequest request(String question, Integer topK, Double minScore) {
        return new ChatRequest(question, null, topK, minScore, null);
    }

    private boolean isValid(ChatRequest request) {
        return VALIDATOR.validate(request).isEmpty();
    }

    @Test
    @DisplayName("问题为空或超长应被拦截")
    void shouldRejectBlankOrTooLongQuestion() {
        assertThat(isValid(request(null, null, null))).as("null 问题").isFalse();
        assertThat(isValid(request("   ", null, null))).as("空白问题").isFalse();
        assertThat(isValid(request("甲".repeat(2001), null, null))).as("超过 2000 字").isFalse();
        assertThat(isValid(request("甲".repeat(2000), null, null))).as("正好 2000 字").isTrue();
    }

    @Test
    @DisplayName("topK 必须在 1~10（文档 §2.2 的上限是 10，不是 20）")
    void shouldEnforceTopKBounds() {
        assertThat(isValid(request("问题", 1, null))).as("下界 1").isTrue();
        assertThat(isValid(request("问题", 10, null))).as("上界 10").isTrue();
        assertThat(isValid(request("问题", 0, null))).as("0 应被拦截").isFalse();
        assertThat(isValid(request("问题", 11, null))).as("11 应被拦截").isFalse();
        assertThat(isValid(request("问题", 20, null))).as("20 应被拦截（旧放行值）").isFalse();
    }

    @Test
    @DisplayName("minScore 必须在 0.3~1.0（0 等于关掉防幻觉闸门）")
    void shouldEnforceMinScoreBounds() {
        assertThat(isValid(request("问题", null, 0.3))).as("下界 0.3").isTrue();
        assertThat(isValid(request("问题", null, 1.0))).as("上界 1.0").isTrue();
        assertThat(isValid(request("问题", null, 0.29))).as("0.29 应被拦截").isFalse();
        assertThat(isValid(request("问题", null, 0.0))).as("0 应被拦截（旧放行值）").isFalse();
        assertThat(isValid(request("问题", null, 1.1))).as("大于 1 应被拦截").isFalse();
        assertThat(isValid(request("问题", null, -0.5))).as("负数应被拦截").isFalse();
    }

    @Test
    @DisplayName("两个检索参数都可为空，表示沿用全局配置")
    void shouldAllowNullRetrievalParameters() {
        assertThat(isValid(request("问题", null, null))).as("不传检索参数应合法").isTrue();
    }

    @Test
    @DisplayName("remember 缺省视为开启")
    void rememberShouldDefaultToTrue() {
        assertThat(request("问题", null, null).rememberOrDefault()).isTrue();
        assertThat(new ChatRequest("问题", null, null, null, Boolean.FALSE).rememberOrDefault()).isFalse();
        assertThat(new ChatRequest("问题", null, null, null, Boolean.TRUE).rememberOrDefault()).isTrue();
    }
}
