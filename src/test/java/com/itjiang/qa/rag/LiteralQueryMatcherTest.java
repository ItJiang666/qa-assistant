package com.itjiang.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字面引用识别的回归测试（决策 47，方案 A）。
 *
 * <p><b>这组测试的重点是"不许误认"</b>。字面匹配比向量检索"硬"：
 * 它一旦认错了引用，就会把无关分块强行塞给模型，而模型会照着那段回答 ——
 * 用户看到的是"答非所问"，完全想不到是检索规则的问题。
 *
 * <p>所以负例比正例更重要：文档里到处是 0.4372、172.1亿 这种数字，
 * 如果把它们当成章节号，每次提问都会捞进一堆图表分块。
 *
 * @author Jiang
 */
class LiteralQueryMatcherTest {

    @Test
    @DisplayName("数字小节号：1.3 / 2.2.1 能认出来")
    void shouldDetectNumericSection() {
        assertThat(LiteralQueryMatcher.from("1.3内容是什么").keys()).contains("1.3");
        assertThat(LiteralQueryMatcher.from("1.3 是什么内容").keys()).contains("1.3");
        assertThat(LiteralQueryMatcher.from("1.3讲的是什么").keys()).contains("1.3");
        assertThat(LiteralQueryMatcher.from("2.2.1 说了什么").keys()).contains("2.2.1");
        assertThat(LiteralQueryMatcher.from("请介绍一下 1.4.2 的内容").keys()).contains("1.4.2");
    }

    @Test
    @DisplayName("小数与数据不许被当成章节号（这是本类最大的误判风险）")
    void shouldNotMistakeDecimalsForSections() {
        assertThat(LiteralQueryMatcher.from("0.4372 是什么意思").isEmpty()).isTrue();
        assertThat(LiteralQueryMatcher.from("172.1亿件是多少").isEmpty()).isTrue();
        assertThat(LiteralQueryMatcher.from("2135亿元是什么概念").isEmpty()).isTrue();
        assertThat(LiteralQueryMatcher.from("同比增长14.9%是多少").isEmpty()).isTrue();
        assertThat(LiteralQueryMatcher.from("11.3 是几号章节").keys()).doesNotContain("1.3");
        assertThat(LiteralQueryMatcher.from("11.3 是几号章节").keys()).contains("11.3");
        // 已知边界：`0.8`、`1.5` 这类"两段各一位"的数，与真实的 `1.3`、`2.2` 章节号
        // **形态完全相同**，无法在不引入猜测的前提下区分。所以这里不设断言 ——
        // 认错它的代价只是多搜一次 "0.8"（命中的分块里真的写着 0.8），不是错误答案。
        // 加"首段不能是 0"之类的启发式属于猜测，与本类"只认形式明确的引用"原则冲突。
    }

    @Test
    @DisplayName("中文章节号：第一章 / 第 3 章 / 第2节")
    void shouldDetectChineseSection() {
        assertThat(LiteralQueryMatcher.from("第一章讲了什么").keys()).anyMatch(k -> k.contains("第一章"));
        assertThat(LiteralQueryMatcher.from("第 3 章的内容").keys()).anyMatch(k -> k.contains("第3章"));
        assertThat(LiteralQueryMatcher.from("第2节是什么").keys()).anyMatch(k -> k.contains("第2节"));
    }

    @Test
    @DisplayName("引号内的原文能认出来（中英文引号都要）")
    void shouldDetectQuotedText() {
        assertThat(LiteralQueryMatcher.from("什么是“曲棍球棒效应”？").keys()).contains("曲棍球棒效应");
        assertThat(LiteralQueryMatcher.from("什么叫\"双十一\"购物狂欢节").keys()).contains("双十一");
    }

    @Test
    @DisplayName("普通提问不该认出任何引用（否则会走错通道）")
    void shouldNotDetectOnPlainQuestions() {
        assertThat(LiteralQueryMatcher.from("D企业面临什么问题").isEmpty()).isTrue();
        assertThat(LiteralQueryMatcher.from("论文的启示是什么").isEmpty()).isTrue();
        assertThat(LiteralQueryMatcher.from("作者用了什么研究方法").isEmpty()).isTrue();
        assertThat(LiteralQueryMatcher.from("曲棍球棒效应是什么").isEmpty()).isTrue();
        assertThat(LiteralQueryMatcher.from("").isEmpty()).isTrue();
        assertThat(LiteralQueryMatcher.from(null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("命中判定：小节号后面要跟章节结束符，1.33 不算命中 1.3")
    void shouldCountHitsOnSegmentText() {
        LiteralQueryMatcher matcher = LiteralQueryMatcher.from("1.3内容是什么");
        assertThat(matcher.hitCount("1.3. 国内外研究现状\n1.3.1.国内研究现状")).isPositive();
        assertThat(matcher.hitCount("见 1.3、国内外研究现状")).isPositive();
        assertThat(matcher.hitCount("1.3 国内外研究现状")).isPositive();
        assertThat(matcher.hitCount("1.33 与 1.331 都不是")).isZero();
        assertThat(matcher.hitCount("数据 17.3 亿元")).isZero();
        assertThat(matcher.hitCount("本研究未涉及该章节")).isZero();
    }

    @Test
    @DisplayName("多段命中要数「次数」而不是「有没有」——它用于排序（实测踩过）")
    void shouldCountAllOccurrencesForRanking() {
        LiteralQueryMatcher matcher = LiteralQueryMatcher.from("1.3节的国内外研究现状讲了什么");
        int three = matcher.hitCount("1.3. 国内外研究现状\n1.3.1.国内研究现状\n1.3.2.国外研究现状");
        int one = matcher.hitCount("1.3. 国内外研究现状（仅此一处）");
        assertThat(three).as("原来写成 find() 只判有没有，两段得分相同、排序失效")
                .isGreaterThan(one);
        assertThat(three).isEqualTo(3);
    }
}
