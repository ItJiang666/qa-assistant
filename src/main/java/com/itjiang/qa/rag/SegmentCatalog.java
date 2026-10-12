package com.itjiang.qa.rag;

import com.itjiang.qa.config.DataDirInitializer;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 可做字面检索的片段目录。
 *
 * <h2>为什么需要它</h2>
 * <p>向量库（{@code InMemoryEmbeddingStore}）的 {@code entries} 字段是<b>包私有</b>的，
 * 跨包拿不到"里面都有哪些片段"，它的公开 API 只能"按向量搜"。
 * 而字面引用检索（见 {@link LiteralQueryMatcher}）需要的是"扫一遍所有片段文本"。
 *
 * <p>三条可能的做法，选了第三条：
 * <ol>
 *   <li>反射读私有字段 —— 被 JDK 模块与后续版本升级反复打脸，不碰；</li>
 *   <li>入库时另存一份目录到数据库 —— 多一份需要同步的状态，迟早漂移；</li>
 *   <li><b>从向量库自己的落盘文件重建</b>（本类）—— 那份 JSON 里本来就完整保存了
 *       每个片段的文本与 metadata，而且它是<b>唯一的真相来源</b>，不会漂移。</li>
 * </ol>
 *
 * <h2>失效与重建</h2>
 * <p>字段变更（入库 / 删除 / 清空）时把目录标记为脏，<b>下次真正需要时才重建</b> ——
 * 重建要读文件并解析 JSON，不该发生在每次入库的路径上。
 * {@code VectorStoreService} 是唯一改向量库的地方，由它调用 {@link #markDirty()}。
 *
 * <p>片段少的时候这点开销可以忽略（实测 49 个片段、0.66 MB 的落盘文件）；
 * 片段多到重建变慢时，这里是最该改成"增量维护"的地方，注释已标明。
 *
 * @author Jiang
 */
@Component
public class SegmentCatalog {

    private static final Logger log = LoggerFactory.getLogger(SegmentCatalog.class);

    /** 与 {@code VectorStoreService.STORE_FILE} 保持一致：向量库的落盘文件名。 */
    private static final String STORE_FILE = "embedding-store.json";

    private final DataDirInitializer dirs;
    private final ObjectMapper objectMapper;

    /** 最近一次重建出的片段（不可变快照）。 */
    private volatile List<TextSegment> segments = List.of();

    /** 目录是否已过期（字段变更后置位）。 */
    private volatile boolean dirty = true;

    public SegmentCatalog(DataDirInitializer dirs, ObjectMapper objectMapper) {
        this.dirs = dirs;
        this.objectMapper = objectMapper;
    }

    /**
     * 标记目录已过期，下次访问时重建。
     *
     * <p>由 {@code VectorStoreService} 在写入 / 删除 / 清空后调用。
     * 不直接重建：那会把"解析 JSON"塞进入库路径，拖慢入库。
     */
    public void markDirty() {
        dirty = true;
    }

    /**
     * 用字面引用检索片段。
     *
     * @param matcher 从提问里提取出的引用；为空时直接返回空列表
     * @param limit   最多返回几条
     * @return 命中的片段，按"命中引用条数"降序；无命中返回空列表
     */
    public List<TextSegment> findByLiteral(LiteralQueryMatcher matcher, int limit) {
        if (matcher == null || matcher.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<TextSegment> all = ensureFresh();
        List<Scored> hits = new ArrayList<>();
        for (TextSegment segment : all) {
            int hits0 = matcher.hitCount(segment.text());
            if (hits0 > 0) {
                hits.add(new Scored(hits0, segment));
            }
        }
        if (hits.isEmpty()) {
            return List.of();
        }
        hits.sort((a, b) -> Integer.compare(b.hits(), a.hits()));
        log.debug("字面引用检索: 引用={}, 命中={} 条", matcher.keys(), hits.size());
        return hits.stream().limit(limit).map(Scored::segment).toList();
    }

    /**
     * 确保目录是最新的，必要时重建。
     *
     * @return 片段列表（不可变）
     */
    private List<TextSegment> ensureFresh() {
        if (!dirty) {
            return segments;
        }
        synchronized (this) {
            if (!dirty) {
                return segments;
            }
            segments = load();
            dirty = false;
            return segments;
        }
    }

    /**
     * 从向量库落盘文件读取全部片段。
     *
     * <p>读不到文件（尚未落盘 / 全新安装）时返回空列表并记一条日志 ——
     * <b>不能抛异常</b>：这条通道是检索的补充，它挂了不该让问答整体失败。
     *
     * @return 片段列表
     */
    private List<TextSegment> load() {
        Path file = dirs.indexDir().resolve(STORE_FILE);
        if (!Files.exists(file)) {
            log.debug("片段目录：落盘文件还不存在，字面检索本轮为空（{}）", file);
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(file));
            JsonNode entries = root.path("entries");
            List<TextSegment> out = new ArrayList<>();
            for (JsonNode entry : entries) {
                JsonNode embedded = entry.path("embedded");
                String text = embedded.path("text").asString("");
                if (text.isEmpty()) {
                    continue;
                }
                out.add(TextSegment.from(text, buildMetadata(embedded.path("metadata"))));
            }
            log.debug("片段目录已重建: {} 个片段（来自 {}）", out.size(), file.getFileName());
            return List.copyOf(out);
        } catch (IOException | RuntimeException ex) {
            // 解析失败最常见的原因是"文件正在被写"（半截 JSON）——下次访问会再试，
            // 所以这里不升级成 ERROR，也不打断问答。
            log.warn("片段目录重建失败（字面检索本轮为空）: {}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * 还原片段的 metadata。
     *
     * <p>落盘格式是 {@code {"metadata": {"fileName": ..., "documentId": ..., "chunkIndex": ...}}} ——
     * 外面那层 {@code metadata} 是 LangChain4j 自己的包装，里面才是我们写入的键。
     * <b>不还原的话，字面命中的片段在引用来源里会没有文件名</b>（实测过）。
     *
     * @param node 落盘 JSON 里的 metadata 节点
     * @return 片段 metadata
     */
    private Metadata buildMetadata(JsonNode node) {
        Metadata metadata = new Metadata();
        JsonNode inner = node.has("metadata") ? node.path("metadata") : node;
        inner.properties().forEach(prop -> {
            JsonNode value = prop.getValue();
            if (value.isNumber()) {
                // 存成 long：documentId / chunkIndex 都是整数，而 Metadata 只有
                // int/long/float/double 的重载，用 long 最不容易截断
                metadata.put(prop.getKey(), value.asLong());
            } else {
                // 布尔也转字符串：Metadata 没有 put(String, boolean) 这个重载。
                // 目前片段 metadata 里只有 fileName / documentId / chunkIndex，
                // 走不到这个分支，留着是为了将来加字段时不至于抛异常。
                metadata.put(prop.getKey(), value.isBoolean()
                        ? String.valueOf(value.asBoolean())
                        : value.asString(""));
            }
        });
        return metadata;
    }

    /**
     * 一次字面命中的中间结果。
     *
     * @param hits    命中的引用条数
     * @param segment 片段
     */
    private record Scored(int hits, TextSegment segment) {
    }
}
