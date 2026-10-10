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
 * <h2>P4 追加的字段：为什么"模式"要拆成三个</h2>
 * <p>因为对话模型的来源可以在运行时切，而向量模型的来源在启动时就定死了
 * （换向量模型等于换向量空间，必须连索引一起重建）。所以一个笼统的 {@code runMode}
 * 会掩盖"对话走云端、向量还在本地"这种完全正常的组合，界面也就无法如实告知用户
 * <b>数据到底会发到哪里</b>。三个字段各司其职：
 * <ul>
 *   <li>{@code runMode} —— 当前对话模式的对外叫法（兼容旧字段）</li>
 *   <li>{@code configuredRunMode} + {@code modeOverridden} —— 配置值，以及"是否被运行时改过"，
 *       界面据此提示"重启后会回到 X"</li>
 *   <li>{@code embeddingRunMode} —— 向量模型属于哪个模式（只读，要改需重启）</li>
 * </ul>
 *
 * @param runMode            当前运行模式：local / cloud
 * @param chatModel          当前对话模型名
 * @param embeddingModel     当前向量模型名
 * @param numCtx             当前上下文窗口设置（可能是 {@code auto} 或具体数字）
 * @param dataDir            运行时数据目录的绝对路径
 * @param database           数据源标识，如 {@code h2} / {@code mysql}
 * @param vectorStore        向量库实现，当前为 {@code InMemoryEmbeddingStore}
 * @param documentCount      已入库文档数
 * @param chunkCount         已入库分块总数
 * @param memoryCount        当前用户的长期记忆条数（仅统计 ACTIVE）
 * @param userIdentified     本次请求是否携带了合法的 {@code X-User-Id}
 * @param userId             当前用户标识；未识别时为 {@code null}
 * @param configuredRunMode  配置里的默认模式（重启后回到的那个值）
 * @param modeOverridden     当前模式是否与配置不同（即被运行时改过）
 * @param embeddingRunMode   向量模型所属模式（只读；换它必须重启并重建索引）
 * @param cloudProvider      云端厂商显示名（当前对话厂商；未配置时为 {@code null}）
 * @param cloudChatProviderId 当前对话厂商的预设 id（界面据此高亮"当前选中"）
 * @param cloudEmbeddingProviderId 当前向量厂商的预设 id
 * @param cloudChatModel     云端对话模型名（用于界面提示"切过去会用哪个模型"）
 * @param cloudEmbeddingModel 云端向量模型名与维度描述
 * @param cloudReady         云端对话端点是否可用（base-url + 模型 + Key 齐备）
 * @param cloudBlockedReason 云端不可用的原因；可用时为 {@code null}
 * @param indexWarning       索引与当前向量模型不一致的提示；一致时为 {@code null}
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
        String userId,
        String configuredRunMode,
        boolean modeOverridden,
        String embeddingRunMode,
        String cloudProvider,
        String cloudChatProviderId,
        String cloudEmbeddingProviderId,
        String cloudChatModel,
        String cloudEmbeddingModel,
        boolean cloudReady,
        String cloudBlockedReason,
        String indexWarning,
        RetrievalSettings retrieval
) {

    /**
     * 当前生效的检索参数（供"系统状态"页展示）。
     *
     * <p><b>为什么把它放进状态接口：</b>用户问"为什么只给我 8 条资料"、
     * "为什么改了配置没生效"时，需要一个能一眼看到<b>实际生效值</b>的地方 ——
     * 只看 yml 是不够的，那里可能有环境变量覆盖、也可能有跑起来后才定下来的值。
     *
     * @param topK              检索条数（实测拐点 8，见开发文档 9.2）
     * @param minScore          相似度阈值
     * @param queryExpansion    查询改写是否开启（实测在该知识库上零增益，默认关）
     * @param extraQueries      改写额外生成几条
     * @param chunkSize         入库分块大小（字）
     * @param chunkOverlap      分块重叠（字）
     */
    public record RetrievalSettings(
            int topK,
            double minScore,
            boolean queryExpansion,
            int extraQueries,
            int chunkSize,
            int chunkOverlap) {
    }
}
