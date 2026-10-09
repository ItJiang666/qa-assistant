package com.itjiang.qa.model.dto;

import java.time.LocalDateTime;

/**
 * 长期记忆视图对象（F12 记忆管理页）。
 *
 * <p>带上 {@code sourceConversationId} 是刻意的：用户看到一条奇怪的记忆时，
 * 最自然的疑问是"它是从哪来的"。有了这个字段就能回溯到那次对话，
 * 而不只是"删掉它，但下次可能又被抽出来"。
 *
 * @param id                    记忆 ID
 * @param memoryType            类型：PREFERENCE / FACT / CONCLUSION
 * @param content               记忆正文
 * @param importance            重要度 1~5
 * @param confidence            置信度 0~1
 * @param sourceConversationId  来源会话
 * @param hitCount              被注入过多少次
 * @param updatedAt             最后更新时间
 * @author Jiang
 */
public record MemoryDto(
        Long id,
        String memoryType,
        String content,
        Integer importance,
        Double confidence,
        String sourceConversationId,
        Integer hitCount,
        LocalDateTime updatedAt
) {
}
