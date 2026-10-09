package com.itjiang.qa.model.dto;

import java.util.List;

/**
 * 问答响应（开发文档 4.2.2）。
 *
 * <p>外层还有统一响应体 {@code ApiResponse}，这里只描述 {@code data} 部分。
 *
 * @param conversationId 本次问答所属会话；请求未带时为服务端新建的标识
 * @param answer         模型回答
 * @param sources        引用的来源片段（P3 阶段起有值，当前为空列表）
 * @param elapsedMs      端到端耗时（毫秒），便于用户判断"慢"到底慢在哪
 * @param runMode        运行模式：local / cloud
 * @param model          实际使用的对话模型名
 * @author Jiang
 */
public record ChatResponse(
        String conversationId,
        String answer,
        List<Source> sources,
        long elapsedMs,
        String runMode,
        String model
) {

    /**
     * 一条引用来源。
     *
     * <p>展示来源不是"锦上添花"，而是这套系统的可信度基础：用户能点开核对，
     * 就敢用；只能看到一段没有出处的文字，就只能靠猜。
     *
     * @param documentId 来源文档 ID
     * @param fileName   来源文件名
     * @param snippet    命中片段原文
     * @param score      相似度得分
     */
    public record Source(
            Long documentId,
            String fileName,
            String snippet,
            Double score
    ) {
    }
}
