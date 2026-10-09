package com.itjiang.qa.rag;

import com.itjiang.qa.config.QaProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 查询改写测试。
 *
 * <p>重点不在"改写得好不好"（那要靠召回评测），而在<b>坏输出不能把检索带崩</b>：
 * 模型经常会加编号、加解释、甚至把答案写进来。这里用桩模型覆盖几种典型脏输出。
 *
 * @author Jiang
 */
class QueryExpanderTest {

    /** 固定返回某个文本的桩模型，避免测试依赖真实模型。 */
    private static ChatModel stub(String reply) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
            }
        };
    }

    private static QaProperties props(boolean enabled, int extra) {
        QaProperties properties = new QaProperties();
        properties.getRetrieval().getQueryExpansion().setEnabled(enabled);
        properties.getRetrieval().getQueryExpansion().setExtraQueries(extra);
        return properties;
    }

    @Test
    @DisplayName("带编号与列表符号的输出要被清洗掉")
    void shouldStripNumberingAndBullets() {
        QueryExpander expander = new QueryExpander(
                stub("1. 空资料占位符 是什么\n2) 防幻觉 机制\n- 检索为空 处理"),
                props(true, 3));

        List<String> queries = expander.expand("检索为空时怎么防止模型乱答");

        assertThat(queries).hasSize(4);
        assertThat(queries.get(0)).isEqualTo("检索为空时怎么防止模型乱答");
        assertThat(queries).containsExactly(
                "检索为空时怎么防止模型乱答", "空资料占位符 是什么", "防幻觉 机制", "检索为空 处理");
    }

    @Test
    @DisplayName("超过条数上限的部分要丢掉")
    void shouldLimitExtraQueries() {
        QueryExpander expander = new QueryExpander(stub("A\nB\nC\nD"), props(true, 2));
        assertThat(expander.expand("q")).containsExactly("q", "A", "B");
    }

    @Test
    @DisplayName("疑似把答案写进来的超长行要丢掉")
    void shouldDropOverlongLines() {
        // 用 repeat 在运行期构造超长行：写死字面量容易算错长度
        // （第一版就踩了：`"a" + "b".repeat(3)` 里的 repeat 只作用于 "b"，结果只有 118 字，刚好没超阈值）
        String longAnswer = "长".repeat(200);
        QueryExpander expander = new QueryExpander(stub(longAnswer + "\n正常的改写式"), props(true, 2));

        assertThat(longAnswer.length()).isGreaterThan(120);
        assertThat(expander.expand("q")).containsExactly("q", "正常的改写式");
    }

    @Test
    @DisplayName("重复的改写式只保留一条")
    void shouldDeduplicate() {
        QueryExpander expander = new QueryExpander(stub("同一个\n同一个\n另一个"), props(true, 3));
        assertThat(expander.expand("q")).containsExactly("q", "同一个", "另一个");
    }

    @Test
    @DisplayName("关闭开关时只返回原问题，且不调用模型")
    void shouldSkipWhenDisabled() {
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                throw new AssertionError("关闭开关时不应调用模型");
            }
        };
        QueryExpander expander = new QueryExpander(failing, props(false, 2));
        assertThat(expander.expand("原问题")).containsExactly("原问题");
    }

    @Test
    @DisplayName("模型调用失败要降级为只用原问题，不能把检索带崩")
    void shouldFallbackOnFailure() {
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                throw new IllegalStateException("模拟模型超时");
            }
        };
        QueryExpander expander = new QueryExpander(failing, props(true, 2));
        assertThat(expander.expand("原问题")).containsExactly("原问题");
    }
}
