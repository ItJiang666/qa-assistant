package com.itjiang.qa.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 从对话中抽取出来的一条记忆（模型输出的 JSON 元素）。
 *
 * <p>字段名必须与提示词里约定的 JSON 键完全一致，否则 Jackson 解析不到。
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 是必须的：
 * 模型经常会多返回一两个字段（比如 {@code reason}），
 * 严格模式下会直接抛异常，导致整批记忆丢失 —— 一个多余的字段不该有这样的破坏力。
 *
 * @param type       记忆类型：PREFERENCE / FACT / CONCLUSION
 * @param key        归一化主题键，模型给的英文短键
 * @param content    记忆正文
 * @param confidence 置信度 0~1
 * @param importance 重要度 1~5
 * @author Jiang
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractedMemory(
        String type,
        String key,
        String content,
        Double confidence,
        Integer importance
) {

    /**
     * 置信度，缺省视为 0。
     *
     * <p>缺省给 0 而不是 1：宁可漏掉一条记忆，也不要让一个没给出置信度的
     * 可疑结果直接入库。
     *
     * @return 置信度
     */
    public double confidenceOrDefault() {
        return confidence == null ? 0.0 : confidence;
    }

    /**
     * 重要度，缺省为 3（中等）。
     *
     * @return 1~5 之间的重要度
     */
    public int importanceOrDefault() {
        int value = importance == null ? 3 : importance;
        return Math.min(Math.max(value, 1), 5);
    }
}
