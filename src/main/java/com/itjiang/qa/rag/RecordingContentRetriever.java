package com.itjiang.qa.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 记录本次检索命中的"检索器装饰器"。
 *
 * <h2>它解决什么问题</h2>
 * <p>{@code @AiService} 上声明 {@code contentRetriever} 之后，框架会把检索到的片段
 * <b>注入 Prompt</b>，但<b>不会把命中结果交回给调用方</b>。而本项目的核心卖点之一
 * 就是"回答必须带出处"——用户要能看到答案出自哪个文档的哪一段。
 *
 * <p>于是用装饰器把真正的检索器包一层：检索照常进行，同时把结果记在
 * 当前线程上，{@code ChatService} 调完模型后取走。
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

    public RecordingContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                     EmbeddingModel embeddingModel,
                                     int defaultTopK,
                                     double defaultMinScore) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.defaultTopK = defaultTopK;
        this.defaultMinScore = defaultMinScore;
        this.defaultRetriever = build(defaultTopK, defaultMinScore);
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
        List<Content> contents = delegate().retrieve(query);

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
        List<Content> effective = (contents == null || contents.isEmpty())
                ? List.of(noContentPlaceholder())
                : contents;

        LAST_RETRIEVAL.set(effective);
        log.debug("检索完成: query='{}', 命中 {} 条{}",
                query.text(), contents == null ? 0 : contents.size(),
                (contents == null || contents.isEmpty()) ? "（已注入空资料占位符）" : "");
        return effective;
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
