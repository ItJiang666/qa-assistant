package com.itjiang.qa.model.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 切换云端"思考模式"的请求体。
 *
 * <p>用 {@code Boolean} 而不是 {@code boolean}：基本类型无法区分"没传"与"传了 false"，
 * 而那两件事的含义完全不同 —— 前者是参数错误，后者是用户明确要关掉思考。
 * 与 {@code ChatModeRequest} 一样，取值校验放在入口（{@link NotNull}），
 * 不让缺字段的请求走到服务层再被当成默认值处理。
 *
 * @param enabled 目标值：{@code true} 开思考，{@code false} 关思考
 * @author Jiang
 */
public record ThinkingModeRequest(
        @NotNull(message = "enabled 不能为空")
        Boolean enabled) {
}
