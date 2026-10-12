package com.itjiang.qa.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 记录本次检索命中的"检索器装饰器"。
 *
 * <h2>它解决什么问题</h2>
 * <p>{@code @AiService} 上声明 {@code contentRetriever} 之后，框架会把检索到的片段
 * <b>注入 Prompt</b>，但<b>不会把命中结果交回给调用方</b>。而本项目的核心卖点之一
 * 就是"回答必须带出处"——用户要能看到答案出自哪个文档的哪一段。
 *
 * <p>于是让本类承担检索器的角色：检索照常进行，同时把结果记在
 * 当前线程上，{@code ChatService} 调完模型后取走。
 * 它内部持有一个用全局配置构建的默认检索器，无覆盖时直接复用；
 * 只有请求带了 topK / minScore 覆盖时才按新参数另建一个。
 *
 * <h2>为什么用 ThreadLocal</h2>
 * <p>检索发生在框架内部，调用方拿不到它的返回值，只能靠"旁路记录"。
 * 而 Spring MVC 的每个请求独占一个线程，用 ThreadLocal 正好做到请求级隔离。
 *
 * <h2>请求级参数覆盖</h2>
 * <p>{@code ChatRequest} 允许每次提问单独指定 {@code topK} / {@code minScore}。
 * 但 {@code @AiService} 引用的是<b>单例</b>检索器，框架调用它时不会把请求参数传进来。
 * 因此同样用 ThreadLocal：{@code ChatService} 在发起调用前
 * {@link #applyOverrides}，调用结束后 {@link #clearOverrides}，
 * {@link #retrieve} 依据当前线程的覆盖值决定用哪个底层检索器。
 * 不覆盖时直接复用启用了全局配置的默认实例，不产生额外对象。
 *
 * <h2>⚠️ 必须在 finally 中清理</h2>
 * <p>Tomcat 复用线程。<b>不清理的话，下一个请求会读到上一个请求的检索结果</b> ——
 * 表现为"回答里引用了完全不相干的文档"，而且是随机复现，极难排查。
 * 因此 {@link #drain()} 是"取出并清空"的语义，{@code ChatService} 必须在 finally 中调用它；
 * 覆盖参数同理，用 {@link #clearOverrides()} 清理。
 *
 * @author Jiang
 */
public class RecordingContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(RecordingContentRetriever.class);

    /**
     * 当前线程最近一次检索的命中结果。
     *
     * <p>用 {@code List.of()} 而不是 {@code null} 表示"没有命中"，
     * 这样调用方不需要做空判断 —— 空集合与 null 混用是空指针的常见来源。
     */
    private static final ThreadLocal<List<Content>> LAST_RETRIEVAL = new ThreadLocal<>();

    /** 当前线程的检索参数覆盖（来自 ChatRequest），未设置时为 {@code null}。 */
    private static final ThreadLocal<Overrides> OVERRIDES = new ThreadLocal<>();

    /** 占位内容的标记键。用于把它从引用来源里剔除。 */
    private static final String PLACEHOLDER_FLAG = "qaNoContentPlaceholder";

    /** 默认检索器引用的向量库，覆盖命中时用它另建检索器。 */
    private final EmbeddingStore<TextSegment> embeddingStore;

    /** 默认检索器引用的向量模型。 */
    private final EmbeddingModel embeddingModel;

    /** 全局配置的 topK，覆盖缺省时的取值。 */
    private final int defaultTopK;

    /** 全局配置的 minScore，覆盖缺省时的取值。 */
    private final double defaultMinScore;

    /** 用全局配置构建的检索器；无覆盖时直接复用它。 */
    private final ContentRetriever defaultRetriever;

    /** 查询改写器；为 {@code null} 时退化为单路检索。 */
    private final QueryExpander queryExpander;

    /** 字面检索用的片段目录；为 {@code null} 时不做字面检索。 */
    private final SegmentCatalog segmentCatalog;

    /** 字面检索是否启用。 */
    private final boolean literalSearchEnabled;

    /** 字面检索最多补进几条候选。 */
    private final int literalMaxResults;

    /** 相对兜底是否启用（绝对阈值放行 0 条时，允许"明显胜出"的候选进来）。 */
    private final boolean relativeFallbackEnabled;

    /** 相对兜底的比值门槛：top1 / top2 达到它才认为"有明显赢家"。 */
    private final double relativeFallbackRatio;

    public RecordingContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                     EmbeddingModel embeddingModel,
                                     int defaultTopK,
                                     double defaultMinScore,
                                     QueryExpander queryExpander,
                                     SegmentCatalog segmentCatalog,
                                     boolean literalSearchEnabled,
                                     int literalMaxResults,
                                     boolean relativeFallbackEnabled,
                                     double relativeFallbackRatio) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.defaultTopK = defaultTopK;
        this.defaultMinScore = defaultMinScore;
        this.defaultRetriever = build(defaultTopK, defaultMinScore);
        this.queryExpander = queryExpander;
        this.segmentCatalog = segmentCatalog;
        this.literalSearchEnabled = literalSearchEnabled;
        this.literalMaxResults = literalMaxResults;
        this.relativeFallbackEnabled = relativeFallbackEnabled;
        this.relativeFallbackRatio = relativeFallbackRatio;
    }

    /**
     * 为本线程设置本次请求的检索参数覆盖。
     *
     * <p>两个参数都可为 {@code null}，表示该维度沿用全局配置。
     * 都为 {@code null} 时等同于 {@link #clearOverrides()}。
     *
     * @param topK     本次检索返回条数，可为 {@code null}
     * @param minScore 本次检索相似度阈值，可为 {@code null}
     */
    public static void applyOverrides(Integer topK, Double minScore) {
        if (topK == null && minScore == null) {
            clearOverrides();
            return;
        }
        OVERRIDES.set(new Overrides(topK, minScore));
    }

    /**
     * 清除本线程的检索参数覆盖。请求结束（含异常路径）必须调用。
     */
    public static void clearOverrides() {
        OVERRIDES.remove();
    }

    @Override
    public List<Content> retrieve(Query query) {
        // ① 向量检索（原路径，一个字没改）：框架传入的 Query 带着上层过滤条件
        List<Content> vectorHits = retrieveWithExpansion(query);

        // ② 字面引用检索（补充通道）：问"1.3 内容是什么"这类**位置型**提问时，
        //    向量检索天生失效（1.3 在向量空间里没有语义），而字面匹配一次就能定位。
        //    实测：含答案的分块向量排名 #21 / 分数 0.2991，字面命中却精确落在答案上。
        List<Content> selected = new ArrayList<>(vectorHits == null ? List.of() : vectorHits);
        int vectorCount = selected.size();
        if (literalSearchEnabled && segmentCatalog != null) {
            LiteralQueryMatcher matcher = LiteralQueryMatcher.from(query.text());
            if (!matcher.isEmpty()) {
                List<Content> literal = segmentCatalog.findByLiteral(matcher, literalMaxResults).stream()
                        .map(Content::from)
                        .toList();
                for (Content content : literal) {
                    // 去重：字面命中的片段可能已经在向量结果里（那就保留向量那条，分数更高）
                    boolean duplicate = selected.stream().anyMatch(existing ->
                            existing.textSegment() != null && content.textSegment() != null
                                    && existing.textSegment().text().equals(content.textSegment().text()));
                    if (!duplicate) {
                        selected.add(content);
                    }
                }
                if (!literal.isEmpty()) {
                    log.debug("字面引用检索命中 {} 条（引用={}），向量命中 {} 条",
                            literal.size(), matcher.keys(), vectorCount);
                }
            }
        }

        // ③ 相对兜底：一条都没选出来时，看有没有"明显胜出"的候选。
        //
        //    为什么需要：绝对阈值 0.65 对**内部高度同质**的文档会整体误杀。
        //    实测同一份文档 7 个"答案确实在库里"的问题只有 3 个能过 0.65 ——
        //    D企业面临什么问题 0.6405、论文的启示是什么 0.4584、1.3 国内外研究现状 0.4372，
        //    全被挡在外面。而它们的 top1/top2 比值明显高于纯噪声提问。
        //    所以第二道判据不看"分数多高"，看"有没有明显赢家"。
        //    实测中它已经起过作用：问"1.3内容是什么"时靠它放进了候选，
        //    否则模型连"资料里都有哪些章节号"都看不到，只能回一句"未找到"。
        int fallbackAdded = 0;
        if (relativeFallbackEnabled && selected.isEmpty()) {
            List<Content> rescued = applyRelativeFallback(query);
            selected.addAll(rescued);
            fallbackAdded = rescued.size();
        }

        // ★ 检索为空时注入一条"占位资料"，这一步是防幻觉的关键。
        //
        //   背景：LangChain4j 的默认内容注入器在 contents 为空时【什么都不注入】，
        //   于是模型看到的 Prompt 里根本没有"资料"这一段。此时系统提示词里
        //   "资料中没有就回答未找到"这条铁律【没有触发条件】—— 模型会合理地把
        //   "没有资料区"理解为"这个问题不需要资料"，转身用自己的训练知识作答。
        //
        //   实测：问"北京是哪个国家的首都"（知识库里没有），修复前模型直接回答
        //   "北京是中华人民共和国的首都……"；修复后如实回答"现有资料中未找到相关信息"。
        //
        //   占位符会被 toSources() 过滤掉，不会作为"引用来源"出现在响应里。
        List<Content> effective = selected.isEmpty()
                ? List.of(noContentPlaceholder())
                : selected;

        LAST_RETRIEVAL.set(effective);
        logRetrieval(query, vectorHits, effective);
        if (fallbackAdded > 0) {
            log.info("相对兜底放行 {} 条候选（绝对阈值一条都没达标）: query='{}'", fallbackAdded, query.text());
        }
        return effective;
    }

    /**
     * 相对兜底：绝对阈值一条都没放行时，用"是否明显胜出"再判一次。
     *
     * <h2>判据为什么是 top1/top2（改阈值前请先复测）</h2>
     * <p>实测比值能把两类分开：
     * <pre>
     *   域外问题（北京是哪个国家的首都）  1.29   ← 但绝对分只有 0.22，被下面的下限挡住
     *   本领域有效（1.3 国内外研究现状）   1.24   ← 有明确赢家，应放行
     *   本领域有效（双十一订单量）        1.03
     *   纯位置问句（1.3内容是什么）       1.06   ← 没有赢家，说明库里真没匹配
     * </pre>
     *
     * <h2>为什么还要一条绝对下限</h2>
     * <p>域外问题的比值有时更高（1.29），只靠比值会把纯噪声放进来。
     * 下限取 {@code minScore * 0.5}：实测有效候选都在 0.43 以上，
     * 而域外问题只有 0.17~0.29，这条下限正好把它们挡住。
     *
     * @param query 原始查询
     * @return 放行的候选；判据不成立时返回空列表
     */
    private List<Content> applyRelativeFallback(Query query) {
        try {
            ContentRetriever fallback = build(effectiveTopK(), 0.0);
            List<Content> candidates = fallback.retrieve(query);
            if (candidates == null || candidates.size() < 2) {
                return List.of();
            }
            double top1 = scoreOf(candidates.get(0));
            double top2 = scoreOf(candidates.get(1));
            double floor = effectiveMinScore() * 0.5;
            if (top2 <= 0 || top1 < floor) {
                return List.of();
            }
            double ratio = top1 / top2;
            if (ratio < relativeFallbackRatio) {
                log.debug("相对兜底未触发: top1={}, top2={}, 比值={} < {}",
                        String.format("%.4f", top1), String.format("%.4f", top2),
                        String.format("%.2f", ratio), relativeFallbackRatio);
                return List.of();
            }
            int keep = Math.min(3, candidates.size());
            log.info("相对兜底命中: top1={}, top2={}, 比值={}，放行前 {} 条",
                    String.format("%.4f", top1), String.format("%.4f", top2),
                    String.format("%.2f", ratio), keep);
            return new ArrayList<>(candidates.subList(0, keep));
        } catch (Exception ex) {
            // 兜底本身失败绝不能影响检索：它只是"多救一次"的尝试
            log.warn("相对兜底失败，维持原结果: {}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * 取一条检索结果的相似度分数。
     *
     * @param content 结果
     * @return 分数；缺失时返回 0
     */
    private double scoreOf(Content content) {
        Object raw = content.metadata().get(ContentMetadata.SCORE);
        return raw instanceof Number number ? number.doubleValue() : 0.0;
    }

    /**
     * 把本次检索的"问题 → 命中列表（含分数与片段开头）"打成一条日志。
     *
     * <p>为什么值得单独写一个方法：检索是整条链路里最难从结果倒推的一步。
     * 模型答"未找到"时，有三种完全不同的原因 —— 检索没命中、命中了但被阈值滤掉、
     * 命中了也注入了但模型没用。只看模型输出无法区分它们，
     * 而这条日志把第一种和第二种直接区分开了（分数就摆在那里）。
     *
     * <p>DEBUG 级：默认不打印，排查时临时把
     * {@code logging.level.com.itjiang.qa.rag} 调到 DEBUG 即可。
     *
     * @param query     原始查询
     * @param raw       底层检索的原始命中（可能为 {@code null}）
     * @param effective 实际注入的内容（可能已被占位符替换）
     */
    private void logRetrieval(Query query, List<Content> raw, List<Content> effective) {
        if (!log.isDebugEnabled()) {
            return;
        }
        StringBuilder out = new StringBuilder();
        out.append("检索明细: query='").append(query.text()).append('\'')
                .append(", 生效 topK=").append(effectiveTopK())
                .append(", minScore=").append(effectiveMinScore())
                .append(", 原始命中=").append(raw == null ? 0 : raw.size())
                .append(", 注入=").append(effective.size());
        int index = 0;
        for (Content content : effective) {
            if (content.textSegment() == null) {
                continue;
            }
            String text = content.textSegment().text();
            out.append("\n  #").append(index++).append(' ')
                    .append(isPlaceholder(content) ? "[占位符]" : "[资料]")
                    .append(text.length() > 40 ? text.substring(0, 40).replace('\n', ' ') + "…" : text.replace('\n', ' '));
        }
        log.debug(out.toString());
    }

    /**
     * 查询改写 + 多路检索 + 轮转合并。
     *
     * <p>改写是<b>可选增强</b>，因此这里有三条降级路径，保证主链路永远不会因为它而失败：
     * 没配改写器 → 单路；改写只返回原问题 → 单路；某一路检索抛异常 → 跳过那一路。
     *
     * @param query 原始查询（含框架传下来的 metadata，改写式必须继承它）
     * @return 合并后的检索结果
     */
    private List<Content> retrieveWithExpansion(Query query) {
        ContentRetriever retriever = delegate();
        if (queryExpander == null) {
            return retriever.retrieve(query);
        }

        List<String> queries = queryExpander.expand(query.text());
        if (queries.size() <= 1) {
            return retriever.retrieve(query);
        }

        List<List<Content>> ranked = new ArrayList<>(queries.size());
        for (String expanded : queries) {
            try {
                // 继承原查询的 metadata（里面带着会话 ID 等框架上下文），只换文本
                ranked.add(retriever.retrieve(Query.from(expanded, query.metadata())));
            } catch (Exception ex) {
                log.warn("多路检索中有一路失败，已跳过: query='{}', 原因={}", expanded, ex.getMessage());
            }
        }
        List<Content> merged = RoundRobinMerge.merge(ranked, effectiveTopK(),
                content -> content.textSegment() == null ? null : content.textSegment().text());
        log.debug("多路检索: {} 路 → 合并去重后 {} 条", queries.size(), merged.size());
        return merged;
    }

    /**
     * 取本次请求生效的 topK（考虑了请求级覆盖）。
     *
     * @return 生效条数
     */
    private int effectiveTopK() {
        Overrides overrides = OVERRIDES.get();
        return overrides == null || overrides.topK() == null ? defaultTopK : overrides.topK();
    }

    /**
     * 取本次请求生效的 minScore（考虑了请求级覆盖）。
     *
     * @return 生效阈值
     */
    private double effectiveMinScore() {
        Overrides overrides = OVERRIDES.get();
        return overrides == null || overrides.minScore() == null ? defaultMinScore : overrides.minScore();
    }

    /**
     * 选定本次检索实际使用的底层检索器。
     *
     * <p>无覆盖、或覆盖值与全局配置完全一致时返回默认实例；否则按覆盖值临时构建。
     *
     * @return 底层检索器
     */
    private ContentRetriever delegate() {
        Overrides overrides = OVERRIDES.get();
        if (overrides == null) {
            return defaultRetriever;
        }
        int topK = overrides.topK() != null ? overrides.topK() : defaultTopK;
        double minScore = overrides.minScore() != null ? overrides.minScore() : defaultMinScore;
        if (topK == defaultTopK && minScore == defaultMinScore) {
            return defaultRetriever;
        }
        log.debug("本次检索使用请求级参数: topK={}, minScore={}", topK, minScore);
        return build(topK, minScore);
    }

    /**
     * 按给定参数构建一个向量检索器。
     *
     * @param topK     返回条数
     * @param minScore 相似度阈值
     * @return 检索器
     */
    private ContentRetriever build(int topK, double minScore) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(topK)
                .minScore(minScore)
                .displayName("qa-knowledge-base")
                .build();
    }

    /**
     * 构造"本次没有检索到资料"的占位内容。
     *
     * <p>措辞刻意写得像一份资料而不是一句系统指令：注入后它会出现在
     * "请根据以下信息回答"的位置上，用资料的口气陈述"没有资料"，
     * 模型更容易按"资料内容"对待，而不是当成一条可以忽略的元指令。
     *
     * @return 占位内容
     */
    private Content noContentPlaceholder() {
        Metadata metadata = new Metadata().put(PLACEHOLDER_FLAG, "true");
        return Content.from(TextSegment.from(
                "（知识库中没有检索到与当前问题相关的任何资料。"
                        + "因此无法回答问题，请如实告知用户：现有资料中未找到相关信息。）",
                metadata));
    }

    /**
     * 判断某条内容是不是"空资料占位符"。
     *
     * <p>用于把它从引用来源里剔除 —— 它不是真的文档片段，
     * 展示给用户会让人以为知识库里真有这么一份文档。
     *
     * @param content 检索内容
     * @return 是占位符返回 {@code true}
     */
    public static boolean isPlaceholder(Content content) {
        return content != null
                && content.textSegment() != null
                && content.textSegment().metadata().getString(PLACEHOLDER_FLAG) != null;
    }

    /**
     * 取出并清空当前线程的检索结果。
     *
     * <p>"取出即清空"是刻意的：调用方只有这一个入口，不可能忘记清理。
     *
     * @return 命中结果，未发生过检索时返回空列表
     */
    public static List<Content> drain() {
        List<Content> contents = LAST_RETRIEVAL.get();
        LAST_RETRIEVAL.remove();
        return contents == null ? List.of() : contents;
    }

    /**
     * 主动清空。
     *
     * <p>用于异常路径：模型调用失败时也要把记录清掉，
     * 否则脏数据会跟着线程活到下一个请求。
     */
    public static void clear() {
        LAST_RETRIEVAL.remove();
    }

    /**
     * 一次请求的检索参数覆盖。
     *
     * @param topK     返回条数，可为 {@code null} 表示沿用全局配置
     * @param minScore 相似度阈值，可为 {@code null} 表示沿用全局配置
     */
    private record Overrides(Integer topK, Double minScore) {
    }
}
