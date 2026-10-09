package com.itjiang.qa.model.dto;

import jakarta.validation.constraints.Size;

/**
 * 修改记忆请求。
 *
 * <p>两个字段都可为空：只改内容、只改重要度、或都改，由 Service 合并处理。
 *
 * @param content    新内容，为空表示不改
 * @param importance 新重要度 1~5，为空表示不改
 * @author Jiang
 */
public record MemoryUpdateRequest(
        @Size(max = 500, message = "记忆内容不能超过 500 字")
        String content,

        Integer importance
) {
}
