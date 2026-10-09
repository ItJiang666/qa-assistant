package com.itjiang.qa.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 手动新增记忆请求（对应前端「记住这条」按钮）。
 *
 * @param content    记忆内容
 * @param importance 重要度 1~5，可为空（默认 4）
 * @author Jiang
 */
public record MemoryCreateRequest(
        @NotBlank(message = "记忆内容不能为空")
        @Size(max = 500, message = "记忆内容不能超过 500 字")
        String content,

        Integer importance
) {
}
