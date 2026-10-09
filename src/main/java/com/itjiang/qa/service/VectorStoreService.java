package com.itjiang.qa.service;

import com.itjiang.qa.common.BusinessException;
import com.itjiang.qa.common.ErrorCode;
import com.itjiang.qa.config.DataDirInitializer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 向量库封装（开发文档 4.5）。
 *
 * <p><b>只暴露业务语义方法，不暴露 {@code EmbeddingStore} 的 API。</b>
 * 上层（入库、检索）调用的是 add / search / removeByDocumentId 这些概念，
 * 而不是 "addAll(embeddings, segments)" 这种库特有签名。
 * 这样换 Qdrant / pgvector 时，改的是一个 Bean 加这个类的内部实现，业务代码零改动。
 *
 * <p><b>关于"文档数/分块数"为什么不用这个类统计：</b>
 * LangChain4j 的 {@code EmbeddingStore} 接口<b>没有 count()</b>，
 * 而 InMemory 实现的内部集合是私有的。与其用反射或维护一个容易和实际不一致的计数器，
 * 不如以数据库 {@code qa_document.chunk_count} 之和为准 —— 那是我们写进去的真值，
 * 而且换任何向量库都成立。
 *
 * @author Jiang
 */
@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    /** 向量库落盘文件名，与 {@code RagConfig} 保持一致。 */
    private static final String STORE_FILE = "embedding-store.json";

    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final DataDirInitializer dirs;

    public VectorStoreService(EmbeddingStore<TextSegment> store,
                              @Qualifier("qaEmbeddingModel") EmbeddingModel embeddingModel,
                              DataDirInitializer dirs) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.dirs = dirs;
    }

    /**
     * 批量写入向量。
     *
     * <p>三个 List 必须<b>一一对应且顺序一致</b>：第 i 个向量是第 i 个片段的意思表示。
     * 错位不会报错，只会让检索结果驴唇不对马嘴 —— 这是最难查的一类 bug，
     * 所以调用方必须用同一次 {@code embedAll} 的返回值。
     *
     * @param embeddings 向量列表
     * @param segments   文本片段列表
     */
    public void add(List<Embedding> embeddings, List<TextSegment> segments) {
        if (embeddings == null || embeddings.isEmpty() || embeddings.size() != segments.size()) {
            throw new IllegalArgumentException(
                    "向量与片段数量不一致: embeddings=" + (embeddings == null ? "null" : embeddings.size())
                            + ", segments=" + segments.size());
        }
        store.addAll(embeddings, segments);
    }

    /**
     * 相似度检索。
     *
     * @param query    查询文本（内部会做一次向量化）
     * @param topK     返回条数上限
     * @param minScore 相似度阈值，低于它的结果直接丢弃 —— 防幻觉的第一道闸门
     * @return 命中结果，按相似度降序；无命中时返回空列表
     */
    public List<SearchHit> search(String query, int topK, double minScore) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(minScore)
                .build();

        EmbeddingSearchResult<TextSegment> result;
        try {
            result = store.search(request);
        } catch (IllegalArgumentException ex) {
            // 维度不一致时 LangChain4j 会抛：
            //   "Length of vector a (2560) must be equal to the length of vector b (1024)"
            // 裸抛出去只会变成"系统内部错误"，用户完全不知道该怎么办。这里翻译成可行动的话。
            // 成因只有一个：索引是用某个向量模型建的，而现在换了另一个（维度或向量空间不同）。
            throw new BusinessException(ErrorCode.INDEX_MODEL_MISMATCH, indexMismatchMessage(ex));
        }
        return result.matches().stream().map(this::toHit).toList();
    }

    /**
     * 把维度不一致的技术异常翻译成用户能照做的提示。
     *
     * <p>提示里必须包含三样：**发生了什么**、**为什么**、**点哪里能修**。
     * 只说"维度不一致"等于把排查成本转嫁给用户。
     *
     * @param ex 原始异常
     * @return 可读提示
     */
    private String indexMismatchMessage(IllegalArgumentException ex) {
        return "向量索引与当前向量模型不匹配：" + ex.getMessage()
                + "。说明索引是用另一个向量模型建立的，检索无法进行。"
                + "请在「文档管理」页点击「重建索引」，用当前模型重新生成全部向量后再提问"
                + "（若刚刚切换过本地/云端模式，切换向量模型都会导致这个情况）。";
    }

    /**
     * 按文档 ID 删除其全部向量。
     *
     * <p>用 metadata 过滤而不是逐个记录向量 ID：删除文档时只需要知道文档 ID，
     * 不必额外维护一张"文档 → 向量 ID"的映射表。
     * 代价是过滤依赖 metadata 里确实写了 documentId —— 入库时务必打上标。
     *
     * @param documentId 文档 ID
     */
    public void removeByDocumentId(Long documentId) {
        store.removeAll(MetadataFilterBuilder.metadataKey("documentId").isEqualTo(documentId));
        log.info("已从向量库移除文档 {} 的向量", documentId);
    }

    /**
     * 清空整个向量库。
     *
     * <p>全量重建索引（F10）的第一步。必须<b>先清空再重灌</b>：
     * 换 embedding 模型后，旧向量与新向量处于不同向量空间，
     * 混在一起不会报错，只会让检索结果随机地烂掉。
     */
    public void clear() {
        store.removeAll();
        log.warn("向量库已清空");
    }

    /**
     * 把向量库落盘（决策 5）。
     *
     * <p>只对 {@code InMemoryEmbeddingStore} 生效。换成自带持久化的库（Qdrant / pgvector）时，
     * 这里会因 {@code instanceof} 不成立而自动跳过，不需要改代码 ——
     * 这正是把持久化放在封装层内部、而不是泄露到上层的价值。
     *
     * <p>失败只记 WARN：落盘是"锦上添花"，不该让一次成功的入库因为写文件失败而报错。
     */
    public void persist() {
        if (!(store instanceof InMemoryEmbeddingStore<TextSegment> inMemory)) {
            return;
        }
        Path file = dirs.indexDir().resolve(STORE_FILE);
        try {
            inMemory.serializeToFile(file);
            log.debug("向量库已落盘: {}", file);
        } catch (Exception ex) {
            log.warn("向量库落盘失败（不影响本次入库）: {}: {}", file, ex.getMessage());
        }
    }

    /**
     * 命中结果 → 业务视图对象。
     *
     * @param match 向量命中
     * @return 检索命中
     */
    private SearchHit toHit(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        Long documentId = segment.metadata().getLong("documentId");
        String fileName = segment.metadata().getString("fileName");
        return new SearchHit(segment.text(), match.score(), documentId, fileName);
    }

    /**
     * 一条检索命中。
     *
     * @param text       命中的原文片段
     * @param score      相似度得分
     * @param documentId 来源文档 ID
     * @param fileName   来源文件名
     */
    public record SearchHit(String text, Double score, Long documentId, String fileName) {
    }
}
