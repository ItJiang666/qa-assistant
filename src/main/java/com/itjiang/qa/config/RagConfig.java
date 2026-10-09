package com.itjiang.qa.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
