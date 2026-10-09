package com.itjiang.qa.config;

import com.itjiang.qa.rag.RecordingContentRetriever;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 向量库装配（开发文档 4.5 / 决策 5）。
 *
 * <p><b>Bean 类型声明成接口 {@code EmbeddingStore<TextSegment>}，而不是具体实现。</b>
 * 这是"换库只改一个 Bean"这句话能成立的前提：注入方看到的始终是接口。
 * 将来换 Qdrant / pgvector，改这一个方法的返回，上层一行不动。
 *
 * <p>起步用 {@code InMemoryEmbeddingStore}（零外部依赖）+ 落盘（重启不丢），
 * 两者结合解决了"开发期不想装数据库、又不想每次重启重新入库"的矛盾。
 *
 * @author Jiang
 */
@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    /** 向量库落盘文件名。 */
    private static final String STORE_FILE = "embedding-store.json";

    /**
     * 检索器（开发文档 4.3.2 / F5）。
     *
     * <p>两个参数的取值直接决定回答质量，改动前请先想清楚：
     * <ul>
     *   <li><b>{@code maxResults}（topK，默认 4）</b> —— 给模型的资料条数。
     *       太少会漏掉关键段落；太多会把上下文挤满（4.6.6 的预算表里检索占 ~2000 token）。</li>
     *   <li><b>{@code minScore}（默认 0.6）</b> —— <b>防幻觉的第一道闸门</b>。
     *       低于阈值的结果宁可不给模型。这一条比 Prompt 里的任何措辞都管用：
     *       模型没看到资料时，"我不知道"是它唯一合理的选择。</li>
     * </ul>
     *
     * <p>外面包了一层 {@link RecordingContentRetriever}：框架只负责把检索结果注入 Prompt，
     * 不会回传给调用方，而我们需要把它作为"引用来源"返回给前端。
     *
     * @param store      向量库
     * @param model      向量模型
     * @param properties 应用配置（topK / minScore）
     * @return 带记录能力的检索器
     */
    @Bean("qaContentRetriever")
    public ContentRetriever qaContentRetriever(EmbeddingStore<TextSegment> store,
                                               @Qualifier("qaEmbeddingModel") EmbeddingModel model,
                                               QaProperties properties) {
        int topK = properties.getRetrieval().getTopK();
        double minScore = properties.getRetrieval().getMinScore();
        log.info("检索器就绪: topK={}, minScore={}（低于阈值的结果不会交给模型）", topK, minScore);

        // 把向量库与向量模型直接交给装饰器：请求级覆盖（ChatRequest.topK/minScore）
        // 需要按新参数临时另建检索器，因此它必须自己持有这两个依赖
        return new RecordingContentRetriever(store, model, topK, minScore);
    }

    /**
     * 向量库。
     *
     * <p>启动时如果落盘文件存在就载入，否则用空库。
     * <b>载入失败不阻断启动</b>：向量库损坏只应导致"检索不到内容"，
     * 而不该让整个应用起不来 —— 后者会让用户连上传入口都进不去，
     * 反而无法通过重新上传来自救。
     *
     * @param dirs 数据目录初始化器
     * @return 向量库实例
     */
    @Bean
    public EmbeddingStore<TextSegment> qaEmbeddingStore(DataDirInitializer dirs) {
        Path storeFile = dirs.indexDir().resolve(STORE_FILE);
        if (Files.exists(storeFile)) {
            try {
                InMemoryEmbeddingStore<TextSegment> loaded = InMemoryEmbeddingStore.fromFile(storeFile);
                log.info("已从 {} 载入向量库", storeFile);
                return loaded;
            } catch (Exception ex) {
                log.warn("向量库载入失败，将以空库启动（需要重新入库文档）: {}: {}",
                        storeFile, ex.getMessage());
            }
        }
        log.info("向量库为空库，落盘位置: {}", storeFile);
        return new InMemoryEmbeddingStore<>();
    }
}
