package com.itjiang.qa.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 切换对话模式的请求体。
 *
 * <p>只允许 {@code local} / {@code cloud} 两个值，且用注解在入口处拦掉拼错的值 ——
 * 不能让"随便一个字符串"走到服务层再被静默当成 local（那是很难查的一类问题：
 * 用户以为切到了云端，实际还在本地）。
 *
 * @param mode 目标模式：{@code local} 或 {@code cloud}
 * @author Jiang
 */
public record ChatModeRequest(
        @NotBlank(message = "mode 不能为空")
        @Pattern(regexp = "local|cloud", message = "mode 只能是 local 或 cloud")
        String mode) {
}
