package com.itjiang.qa.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 空回答判定测试。
 *
 * <p>用例不是凭空想的：{@code []}、{@code {}}、纯标点这几种形态都来自
 * 实测观察或模型输出的常见退化形式。特别是 {@code []} —— 它真的发生过两次
 * （一次云端 DeepSeek、一次本地 qwen3.5:9b），而当时的判据是 {@code isBlank}，
 * 抓不到它，于是两个方括号被当成正式回答写进了对话历史。
 *
 * @author Jiang
 */
class AnswerGuardTest {

    @Test
    @DisplayName("没有实质内容的回答都应判为空")
    void shouldTreatContentlessAnswersAsEmpty() {
        assertThat(AnswerGuard.isEmpty(null)).isTrue();
        assertThat(AnswerGuard.isEmpty("")).isTrue();
        assertThat(AnswerGuard.isEmpty("   ")).isTrue();
        assertThat(AnswerGuard.isEmpty("\n\t")).isTrue();
        // 实测发生过的形态
        assertThat(AnswerGuard.isEmpty("[]")).isTrue();
        assertThat(AnswerGuard.isEmpty(" [ ] ")).isTrue();
        assertThat(AnswerGuard.isEmpty("{}")).isTrue();
        // 只有引号 / 只有标点 / 只有数字
        assertThat(AnswerGuard.isEmpty("\"\"")).isTrue();
        assertThat(AnswerGuard.isEmpty("''")).isTrue();
        assertThat(AnswerGuard.isEmpty("。。。")).isTrue();
        assertThat(AnswerGuard.isEmpty("---")).isTrue();
        assertThat(AnswerGuard.isEmpty("0")).isTrue();
    }

    @Test
    @DisplayName("有实质内容的回答不能被误判")
    void shouldNotTreatRealAnswersAsEmpty() {
        assertThat(AnswerGuard.isEmpty("现有资料中未找到相关信息。")).isFalse();
        assertThat(AnswerGuard.isEmpty("你好")).isFalse();
        assertThat(AnswerGuard.isEmpty("[] ")).isTrue();          // 括号后带空格仍为空
        assertThat(AnswerGuard.isEmpty("[] 如下")).isFalse();      // 括号 + 文字 = 有内容
        assertThat(AnswerGuard.isEmpty("（暂无）")).isFalse();
        assertThat(AnswerGuard.isEmpty("JDK 17")).isFalse();
        assertThat(AnswerGuard.isEmpty("ZD-1")).isFalse();
        assertThat(AnswerGuard.isEmpty("A")).isFalse();
    }

    @Test
    @DisplayName("替代文案必须给出下一步动作，而不是只说失败了")
    void messageShouldTellUserWhatToDo() {
        String message = AnswerGuard.emptyAnswerMessage();
        assertThat(message).contains("重试");
        assertThat(message).contains("已记录");
    }
}
