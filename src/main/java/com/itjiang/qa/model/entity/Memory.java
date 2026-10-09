package com.itjiang.qa.model.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 长期记忆（表 {@code qa_memory}）—— L3。
 *
 * <p>与 L2 会话记忆的本质区别：L2 属于"某次对话"，L3 属于"某个人"。
 * 换个会话，L2 就归零；L3 跟着 user_id 走，所以新开对话它仍然认识你。
 *
 * <p><b>{@code memKey} 是这张表不膨胀的关键</b>：同一用户的同一主题只允许一条
 * （唯一约束 {@code uk_qa_memory_user_key}）。抽取到新内容时走 upsert 覆盖，
 * 于是"我喜欢简洁 / 用户偏好简洁回答 / 别写太长"会收敛成一条，
 * 而不是堆三条意思差不多的记忆。
 *
 * @author Jiang
 */
public class Memory {

    private Long id;

    /** 归属人（匿名 UUID，来自请求头 X-User-Id）。 */
    private String userId;

    /** PREFERENCE / FACT / CONCLUSION。 */
    private String memoryType;

    /** 归一化主题键，如 answer_length。用于去重合并。 */
    private String memKey;

    /** 记忆正文，一句话。 */
    private String content;

    /** 置信度 0.00~1.00，低于阈值的抽取结果不写入。 */
    private BigDecimal confidence;

    /** 重要度 1~5，注入排序用。 */
    private Integer importance;

    /** 来源会话，便于追溯与回滚。 */
    private String sourceConvId;

    /** ACTIVE / DELETED（软删，保留可追溯）。 */
    private String status;

    /** 被注入过多少次，用于淘汰判断。 */
    private Integer hitCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getMemoryType() {
        return memoryType;
    }

    public void setMemoryType(String memoryType) {
        this.memoryType = memoryType;
    }

    public String getMemKey() {
        return memKey;
    }

    public void setMemKey(String memKey) {
        this.memKey = memKey;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

    public Integer getImportance() {
        return importance;
    }

    public void setImportance(Integer importance) {
        this.importance = importance;
    }

    public String getSourceConvId() {
        return sourceConvId;
    }

    public void setSourceConvId(String sourceConvId) {
        this.sourceConvId = sourceConvId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getHitCount() {
        return hitCount;
    }

    public void setHitCount(Integer hitCount) {
        this.hitCount = hitCount;
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
