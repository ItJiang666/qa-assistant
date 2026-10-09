package com.itjiang.qa.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 中文分块器测试。
 *
 * <p>这个测试存在的理由：LangChain4j 自带的分块器对中文是**静默失效**的 ——
 * 它不报错、不告警，只是把长中文段落从句子中间切开。
 * 这类问题在功能测试里看不出来（文档照样入库、照样能检索），
 * 只有直接检查分块边界才能发现。
 *
 * @author Jiang
 */
class ChineseDocumentSplittersTest {

    /** 造一段没有换行的长中文文本，每个句子以「。」结尾。 */
    private String longChineseParagraph(int sentenceCount) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= sentenceCount; i++) {
            text.append("这是第").append(i).append("句测试文本，用来验证分块边界是否落在句号之后而不会把句子切断。");
        }
        return text.toString();
    }

    @Test
    @DisplayName("长中文段落应在句号处切分，而不是按字符硬切")
    void shouldSplitAtChineseSentenceBoundary() {
        String text = longChineseParagraph(20);
        // chunkSize=200、overlap=0：每块约 4 句，边界必须落在「。」之后
        List<TextSegment> segments = ChineseDocumentSplitters.recursive(200, 0).split(Document.from(text));

        assertThat(segments).as("应当切出多块").hasSizeGreaterThan(1);

        for (int i = 0; i < segments.size(); i++) {
            String chunk = segments.get(i).text();
            char last = chunk.charAt(chunk.length() - 1);
            assertThat(last)
                    .as("第 %d 块的结尾字符是「%s」，说明被从句子中间切断了：…%s",
                            i + 1, last, tail(chunk))
                    .isEqualTo('。');
        }
    }

    @Test
    @DisplayName("分块不应丢失内容（去掉重叠后能拼回原文）")
    void shouldNotLoseContent() {
        String text = longChineseParagraph(12);
        List<TextSegment> segments = ChineseDocumentSplitters.recursive(200, 0).split(Document.from(text));

        String joined = String.join("", segments.stream().map(TextSegment::text).toList());
        // 分块可能引入空白差异，比较时去掉所有空白
        assertThat(joined.replaceAll("\\s+", ""))
                .as("分块后拼接应与原文一致")
                .isEqualTo(text.replaceAll("\\s+", ""));
    }

    @Test
    @DisplayName("单句超长时仍能切分（不能因为切不动就无限长）")
    void shouldStillSplitOverlongSentence() {
        // 造一个没有任何标点的 800 字长句
        String oneLongSentence = "甲".repeat(800);
        List<TextSegment> segments = ChineseDocumentSplitters.recursive(200, 0).split(Document.from(oneLongSentence));

        assertThat(segments).hasSizeGreaterThan(1);
        for (TextSegment segment : segments) {
            assertThat(segment.text().length())
                    .as("每块都不应超过 chunkSize")
                    .isLessThanOrEqualTo(200);
        }
    }

    @Test
    @DisplayName("英文段落仍按句子切分（不能因为改中文而破坏英文）")
    void shouldStillSplitEnglishSentences() {
        String english = ("This is a test sentence for verifying the splitter behaviour. ").repeat(12);
        List<TextSegment> segments = ChineseDocumentSplitters.recursive(200, 0).split(Document.from(english));

        assertThat(segments).hasSizeGreaterThan(1);
        for (TextSegment segment : segments) {
            assertThat(segment.text().trim())
                    .as("英文块不应以半个单词结尾")
                    .endsWith(".");
        }
    }

    private String tail(String chunk) {
        return chunk.length() <= 30 ? chunk : "…" + chunk.substring(chunk.length() - 30);
    }
}
