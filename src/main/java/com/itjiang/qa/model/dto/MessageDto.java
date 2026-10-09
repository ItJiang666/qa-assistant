package com.itjiang.qa.model.dto;

import java.time.LocalDateTime;

/**
 * 消息列表项。
 *
 * @param id          消息 ID
 * @param role        角色：user / assistant
 * @param content     内容
 * @param sourcesJson 引用来源的原始 JSON（P3 起有值；前端按需反序列化）
 * @param createdAt   时间
 * @author Jiang
 */
public record MessageDto(
        Long id,
        String role,
        String content,
        String sourcesJson,
        LocalDateTime createdAt
) {
}
