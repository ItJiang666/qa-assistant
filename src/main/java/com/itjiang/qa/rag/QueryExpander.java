package com.itjiang.qa.rag;

import com.itjiang.qa.config.QaProperties;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 查询改写：把用户的问法改写成"文档可能使用的说法"，用于多路检索。
 *
 * <h2>为什么需要它（而不是继续调 topK）</h2>
 * <p>召回评测给出了明确边界：把 topK 从 4 提到 8，命中率 79% → 93%，再往上（12、20）毫无收益。
 * 剩下那一条漏检<b>不是"检索算得不准"，而是"用词对不上"</b> ——
 * 用户问"检索为空时怎么防止模型乱答"，文档里写的是"空资料占位符""防幻觉"，
 * 两个字面完全不相交。向量检索对这种同义不同词无能为力，改写是直接的解法。
 *
 * <h2>为什么用抽取模型（温度 0）而不是问答模型</h2>
 * <p>改写要的是<b>稳定与可解析</b>（一行一条、不要解释、不要客套），不是文采。
 * 用温度 0 的模型能显著减少"好的，以下是改写结果：1. …"这类需要额外清洗的输出。
 *
 * <h2>失败怎么办</h2>
 * <p><b>一律降级为"只用原问题检索"</b>，绝不因为一个可选的增强手段而让整个问答失败。
 * 这个取舍很重要：改写是锦上添花，检索是主链路，主链路不能被人质劫持。
 *
 * @author Jiang
 */
public class QueryExpander {

    private static final Logger log = LoggerFactory.getLogger(QueryExpander.class);

    /**
     * 改写提示词。
     *
     * <p>三条约束都是必要的：
     * <ul>
     *   <li>"只改措辞、不得改变原意" —— 否则会检索到别的东西</li>
     *   <li>"不要回答问题" —— 否则模型直接开始作答（那是生成，不是检索）</li>
     *   <li>"每行一条、不要编号" —— 输出可直接按行切分，省掉一层脆弱的解析</li>
     * </ul>
     */
    private static final String PROMPT = """
            你是检索助手。把下面这个问题改写成 %d 条"更适合在技术文档里检索"的查询式。

            要求：
            1. 只改措辞：同义词替换、把口语换成书面语、补上可能出现的专业术语
            2. 不得改变原意，不得引入原问题没有的信息
            3. 不要回答问题，只输出改写后的查询式
            4. 每行一条，不要编号、不要标点前缀、不要任何解释

            问题：%s
            """;

    /** 单条改写式的长度上限：防止模型把答案写进来。 */
    private static final int MAX_QUERY_CHARS = 120;

    private final ChatModel chatModel;
    private final QaProperties properties;

    public QueryExpander(ChatModel chatModel, QaProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    /**
     * 生成检索式列表。
     *
     * <p>返回值的<b>第一条永远是原始问题</b>：它代表用户真正问的东西，
     * 而改写只是补充视角。合并时也按这个优先级轮转（见 {@link RoundRobinMerge}）。
     *
     * @param question 用户问题
     * @return 检索式列表；关闭或无有效改写时只含原问题
     */
    public List<String> expand(String question) {
        List<String> queries = new ArrayList<>();
        queries.add(question);

        QaProperties.Retrieval.QueryExpansion cfg = properties.getRetrieval().getQueryExpansion();
        if (!cfg.isEnabled() || cfg.getExtraQueries() <= 0) {
            return queries;
        }

        long start = System.currentTimeMillis();
        try {
            String raw = chatModel.chat(PROMPT.formatted(cfg.getExtraQueries(), question));
            queries.addAll(parse(raw, cfg.getExtraQueries()));
            log.debug("查询改写: 原问题 + {} 条改写，耗时 {} ms",
                    queries.size() - 1, System.currentTimeMillis() - start);
        } catch (Exception ex) {
            // 增强手段失败不影响主链路：退回"只用原问题检索"
            log.warn("查询改写失败，本次只用原问题检索: {}", ex.getMessage());
        }
        return queries;
    }

    /**
     * 解析模型输出。
     *
     * <p>容错要点：按行切分、去掉编号与列表符号、丢掉过长（疑似把答案写进来）与重复的行。
     *
     * @param raw   模型原始输出，可为 {@code null}
     * @param limit 最多取几条
     * @return 改写式列表（可能为空）
     */
    List<String> parse(String raw, int limit) {
        Set<String> result = new LinkedHashSet<>();
        if (raw == null) {
            return List.of();
        }
        for (String line : raw.split("\\r?\\n")) {
            String cleaned = line.trim()
                    // 去掉 "1. " "- " "* " "1) " 这类前缀
                    .replaceFirst("^\\s*(\\d+\\s*[.、)）]|[-*•])\\s*", "")
                    .trim();
            if (cleaned.isEmpty() || cleaned.length() > MAX_QUERY_CHARS) {
                continue;
            }
            result.add(cleaned);
            if (result.size() >= limit) {
                break;
            }
        }
        return List.copyOf(result);
    }
}
