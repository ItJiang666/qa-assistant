package com.itjiang.qa.model.dto;

import java.util.List;

/**
 * 检索试跑结果。
 *
 * <p>刻意把命中片段的**正文**也返回：调参时要判断的是"命中的到底是不是该命中的那一节"，
 * 只看分数与文件名判断不了 —— 同一份文档里可能有 139 个片段，
 * 分数 0.72 的那条究竟是"技术选型"还是"授权与合规"，只有看正文才知道。
 *
 * @param query    本次查询
 * @param topK     实际使用的条数上限
 * @param minScore 实际使用的阈值
 * @param hitCount 命中条数
 * @param hits     命中明细（按相似度降序）
 * @author Jiang
 */
public record SearchResponse(
        String query,
        int topK,
        double minScore,
        int hitCount,
        List<Hit> hits) {

    /**
     * 单条命中。
     *
     * @param documentId 文档 ID
     * @param fileName   文件名
     * @param score      相似度
     * @param text       片段正文（完整返回，便于判断命中的章节对不对）
     */
    public record Hit(Long documentId, String fileName, double score, String text) {
    }
}
