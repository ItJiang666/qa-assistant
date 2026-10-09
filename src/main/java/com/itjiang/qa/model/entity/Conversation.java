package com.itjiang.qa.model.entity;

import java.time.LocalDateTime;

/**
 * 问答会话（表 {@code qa_conversation}）。
 *
 * <p>用普通类而非 {@code record}：实体需要被 MyBatis 通过 setter 填充，
 * 而 {@code record} 只有全参构造器，MyBatis 的映射会变得别扭。
 * 「DTO 用 record、实体用普通类」是这套代码的统一约定（开发文档第 8 章）。
 *
 * @author Jiang
 */
public class Conversation {

    /** 会话 ID（前端生成的 UUID）。 */
    private String id;

    /** 归属人（匿名 UUID，来自请求头 X-User-Id）。 */
    private String userId;

    /** 首问摘要，用于会话列表展示。 */
    private String title;

    /** 运行模式：LOCAL / CLOUD。 */
    private String runMode;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getRunMode() {
        return runMode;
    }

    public void setRunMode(String runMode) {
        this.runMode = runMode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
