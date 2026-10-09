package com.itjiang.qa.model.dto;

/**
 * 系统状态响应（F9）。
 *
 * <p>用途不只是"显示几个数字"：它是排查问题的第一现场。用户报"回答很慢""数据不见了"
 * 时，先看这个接口就能定位到模式、模型、数据目录、库类型。
 *
 * <p>用 {@code record} 而非普通类：状态对象天然不可变，不需要 setter，
 * 序列化行为也由字段顺序唯一确定。
 *
 * @param runMode        当前运行模式：LOCAL / CLOUD
 * @param chatModel      当前对话模型名
 * @param embeddingModel 当前向量模型名
 * @param numCtx         当前上下文窗口设置（可能是 {@code auto} 或具体数字）
 * @param dataDir        运行时数据目录的绝对路径
 * @param database       数据源标识，如 {@code h2} / {@code mysql}
 * @param vectorStore    向量库实现，当前为 {@code InMemoryEmbeddingStore}
 * @param documentCount  已入库文档数
 * @param chunkCount     已入库分块总数
 * @param memoryCount    当前用户的长期记忆条数（仅统计 ACTIVE）
 * @param userIdentified 本次请求是否携带了合法的 {@code X-User-Id}
 * @param userId         当前用户标识；未识别时为 {@code null}
 * @author Jiang
 */
public record SystemStatusResponse(
        String runMode,
        String chatModel,
        String embeddingModel,
        String numCtx,
        String dataDir,
        String database,
        String vectorStore,
        long documentCount,
        long chunkCount,
        long memoryCount,
        boolean userIdentified,
        String userId
) {
}
