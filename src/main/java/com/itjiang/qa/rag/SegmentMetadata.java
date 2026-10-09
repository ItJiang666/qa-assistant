package com.itjiang.qa.rag;

/**
 * 向量片段 metadata 的键名约定。
 *
 * <p>集中定义的原因：这些键在<b>写入侧</b>（入库流水线打标）和<b>读取侧</b>
 * （检索结果回填引用来源、按文档删除向量）都要用到。
 * 分散在两处写字符串字面量，改了一个忘了另一个时不会有任何编译错误 ——
 * 只会表现为"引用来源显示不出文件名"或者"删了文档向量还在"。
 *
 * @author Jiang
 */
public final class SegmentMetadata {

    /** 文档 ID。删除向量时按它过滤，是这个键最重要的用途。 */
    public static final String DOCUMENT_ID = "documentId";

    /** 原始文件名。引用来源展示用。 */
    public static final String FILE_NAME = "fileName";

    /** 分块序号，从 0 开始。用于同一文档内的定位与排序。 */
    public static final String CHUNK_INDEX = "chunkIndex";

    /**
     * 工具类，禁止实例化。
     */
    private SegmentMetadata() {
    }
}
