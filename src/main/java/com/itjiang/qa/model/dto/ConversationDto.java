package com.itjiang.qa.model.dto;

import java.time.LocalDateTime;

/**
 * 会话列表项。
 *
 * @param id        会话 ID
 * @param title     标题（首问摘要）
 * @param runMode   运行模式
 * @param createdAt 创建时间
 * @param updatedAt 最后活跃时间
 * @author Jiang
 */
public record ConversationDto(
        String id,
        String title,
        String runMode,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
