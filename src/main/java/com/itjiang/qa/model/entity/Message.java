package com.itjiang.qa.model.entity;

import java.time.LocalDateTime;

/**
 * 问答消息（表 {@code qa_message}）。
 *
 * <p>这张表身兼两职：既是<b>完整的对话历史</b>（前端要能翻记录），
 * 也是 <b>L2 会话记忆的持久化来源</b>（{@code JdbcChatMemoryStore} 从这里读，
 * 交给 ChatMemory 按 token 窗口裁剪）。一份数据两个用途，因此不能只存裁剪后的窗口 ——
 * 裁剪是"给模型看多少"的策略，不是"存多少"的策略。
 *
 * @author Jiang
 */
public class Message {

    private Long id;

    private String conversationId;

    /** 角色：user / assistant / system / tool。 */
    private String role;

    private String content;

    /** 引用的来源片段（JSON），P3 起有值。 */
    private String sourcesJson;

    /** 本次调用消耗的 token 数，用于成本观测。 */
    private Integer tokenUsage;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSourcesJson() {
        return sourcesJson;
    }

    public void setSourcesJson(String sourcesJson) {
        this.sourcesJson = sourcesJson;
    }

    public Integer getTokenUsage() {
        return tokenUsage;
    }

    public void setTokenUsage(Integer tokenUsage) {
        this.tokenUsage = tokenUsage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
