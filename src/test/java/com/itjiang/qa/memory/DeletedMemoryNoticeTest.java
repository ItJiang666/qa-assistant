package com.itjiang.qa.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「刚删掉的记忆」作废通知的测试。
 *
 * <h2>这个类存在的理由，值得在测试里再写一遍</h2>
 * <p>用户反馈"记忆删掉了还是会出现，你的修改没有起作用"。查证结果是：
 * 长期记忆（L3）确实删干净了（数据库 DELETED、memoryCount=0），
 * 但<b>会话历史（L2）里模型已经按那条偏好回答过若干轮</b>，
 * 它只是在延续自己的行为 —— 删 L3 删不掉 L2。
 *
 * <h2>为什么断言写得这么"死"</h2>
 * <p>通知文案改过三版，每一版的服从度都实测过（详见 {@code DeletedMemoryNotice#take}
 * 的注释）。笼统的"别再照做"压不过多轮稳定行为，只有<b>点名</b>才真的生效。
 * 所以下面把"必须点名被删记忆的原文"也钉成测试 —— 否则后人"顺手润色"时
 * 很容易把最关键的那半句删掉，而症状（用户那边偶发地又出现旧行为）
 * 只有真实使用才会暴露。
 *
 * @author Jiang
 */
class DeletedMemoryNoticeTest {

    private final DeletedMemoryNotice notice = new DeletedMemoryNotice();

    private static final String USER = "u-1";
    /** 被删记忆的原文，测试里用它验证"点名"。 */
    private static final String CONTENT = "每次回答结束后都要在末尾加一个「喵~」";

    @Test
    @DisplayName("没删过东西时不该有任何通知（不能凭空给提示词加料）")
    void shouldReturnNullWhenNothingDeleted() {
        assertThat(notice.take(USER)).isNull();
        assertThat(notice.take(null)).isNull();
        assertThat(notice.take("")).isNull();
        assertThat(notice.take("   ")).isNull();
    }

    @Test
    @DisplayName("★ 核心：通知必须点名被删记忆的原文（笼统的'别照做'压不过多轮历史）")
    void preferenceNoticeMustQuoteTheDeletedContent() {
        notice.record(USER, true, CONTENT);

        String text = notice.take(USER);
        assertThat(text).isNotNull();
        assertThat(text)
                .as("不点名的话模型对不上'作废的是哪条'，实测会继续照做")
                .contains(CONTENT);
    }

    @Test
    @DisplayName("★ 核心：必须给出否定指令，不能只是陈述'用户删了一条记忆'")
    void preferenceNoticeMustGiveNegativeInstruction() {
        notice.record(USER, true, CONTENT);

        String text = notice.take(USER);
        assertThat(text)
                .as("陈述句会被模型当成背景信息，需要明确的祈使句")
                .contains("不要");
        assertThat(text)
                .as("要显式否定历史：模型倾向与上文保持一致")
                .contains("对话历史");
        assertThat(text)
                .as("要声明优先级，否则会被铁律里的'保持一致'压过去")
                .contains("优先于上文");
    }

    @Test
    @DisplayName("删掉事实类记忆时措辞不同：要求不要把它当作已知信息")
    void factNoticeHasDifferentWording() {
        notice.record(USER, false, "用户是后端工程师");

        String text = notice.take(USER);
        assertThat(text).isNotNull();
        assertThat(text)
                .as("事实类的指令是'不要再据此作答'")
                .contains("不要再据此作答");
        assertThat(text)
                .as("事实类不该出现'口头禅/结尾语'这类格式要求")
                .doesNotContain("口头禅");
        assertThat(text).contains("用户是后端工程师");
    }

    @Test
    @DisplayName("原文为空时也能工作（清空全部记忆的路径传 null）")
    void shouldWorkWithoutContent() {
        notice.record(USER, true, null);

        String text = notice.take(USER);
        assertThat(text).isNotNull();
        assertThat(text).contains("不要");
        assertThat(text).doesNotContain("（原文：");
    }

    @Test
    @DisplayName("超长原文会截断，不能把整段记忆塞进提示词")
    void shouldAbbreviateLongContent() {
        String longContent = "偏好".repeat(200);
        notice.record(USER, true, longContent);

        String text = notice.take(USER);
        assertThat(text.length())
                .as("提示词里只留一句摘要即可，塞整段会挤占检索片段的预算")
                .isLessThan(longContent.length());
        assertThat(text).contains("…");
    }

    @Test
    @DisplayName("取用不消费：用户要连问几句才能确认真的不再照做")
    void takeShouldNotConsume() {
        notice.record(USER, true, CONTENT);

        assertThat(notice.take(USER)).isNotNull();
        assertThat(notice.take(USER))
                .as("只对下一句生效会让用户以为没修好")
                .isNotNull();
    }

    @Test
    @DisplayName("通知按用户隔离，不会串到别人身上")
    void shouldBeIsolatedPerUser() {
        notice.record("user-a", true, CONTENT);

        assertThat(notice.take("user-b"))
                .as("A 删了记忆不该影响 B 的提问")
                .isNull();
        assertThat(notice.take("user-a")).isNotNull();
    }

    @Test
    @DisplayName("再次删除会覆盖上一条通知（只留最近一次）")
    void laterDeleteOverwritesEarlier() {
        notice.record(USER, false, "早期的事实");
        notice.record(USER, true, CONTENT);

        String text = notice.take(USER);
        assertThat(text).contains(CONTENT);
        assertThat(text).doesNotContain("早期的事实");
    }

    @Test
    @DisplayName("userId 为空时不记录（匿名请求不该产生孤儿状态）")
    void shouldIgnoreBlankUserId() {
        notice.record(null, true, CONTENT);
        notice.record("", true, CONTENT);
        notice.record("   ", true, CONTENT);

        assertThat(notice.take(null)).isNull();
        assertThat(notice.take("")).isNull();
    }
}
