package com.itjiang.qa.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多路检索合并测试。
 *
 * <p>这个合并规则有两个容易写错的地方，也正是用例要盯住的：
 * <ol>
 *   <li><b>必须轮转，不能按分数混排</b> —— 不同检索式的分数不可比，
 *       混排会让改写式的高分片段把原问题的结果全挤掉</li>
 *   <li><b>必须去重</b> —— 同一段文字常被多路同时命中，不去重就等于浪费上下文预算</li>
 * </ol>
 *
 * @author Jiang
 */
class RoundRobinMergeTest {

    private static Content content(String text) {
        return Content.from(TextSegment.from(text));
    }

    @Test
    @DisplayName("轮转合并：每一路都要有代表，且原问题排最前")
    void shouldInterleaveLists() {
        List<List<Content>> ranked = List.of(
                List.of(content("原1"), content("原2")),
                List.of(content("改A1"), content("改A2")),
                List.of(content("改B1"), content("改B2")));

        List<Content> merged = RoundRobinMerge.merge(ranked, 6, c -> c.textSegment().text());

        assertThat(merged).extracting(c -> c.textSegment().text())
                .containsExactly("原1", "改A1", "改B1", "原2", "改A2", "改B2");
    }

    @Test
    @DisplayName("按正文去重：同一片段被多路命中只保留一次")
    void shouldDeduplicateByText() {
        List<List<Content>> ranked = List.of(
                List.of(content("同一段"), content("原2")),
                List.of(content("同一段"), content("改A2")));

        List<Content> merged = RoundRobinMerge.merge(ranked, 8, c -> c.textSegment().text());

        assertThat(merged).extracting(c -> c.textSegment().text())
                .containsExactly("同一段", "原2", "改A2");
    }

    @Test
    @DisplayName("截断到 limit，且先满足靠前的路")
    void shouldRespectLimit() {
        List<List<Content>> ranked = List.of(
                List.of(content("原1"), content("原2"), content("原3")),
                List.of(content("改A1"), content("改A2")));

        List<Content> merged = RoundRobinMerge.merge(ranked, 3, c -> c.textSegment().text());

        assertThat(merged).extracting(c -> c.textSegment().text())
                .containsExactly("原1", "改A1", "原2");
    }

    @Test
    @DisplayName("空输入与异常输入不能抛异常（检索增强绝不能拖垮主链路）")
    void shouldTolerateEdgeCases() {
        assertThat(RoundRobinMerge.merge(null, 5, c -> null)).isEmpty();
        assertThat(RoundRobinMerge.merge(List.of(), 5, c -> null)).isEmpty();
        assertThat(RoundRobinMerge.merge(List.of(List.of(content("a"))), 0, c -> null)).isEmpty();
        // 某一路为空（该路没命中）时其余路照常合并
        List<Content> merged = RoundRobinMerge.merge(
                java.util.Arrays.asList(null, List.of(content("只有这一路"))), 5,
                c -> c.textSegment().text());
        assertThat(merged).hasSize(1);
    }
}
