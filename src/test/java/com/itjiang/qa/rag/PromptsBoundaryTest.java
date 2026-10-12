package com.itjiang.qa.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 提示词边界的回归测试（决策 45）。
 *
 * <p><b>为什么提示词也值得写测试：</b>它是这个产品唯一的"行为规约"，
 * 而且改起来毫无摩擦 —— 加一句话就可能把一整类问题变成拒答，且不会有任何编译错误。
 * 实测就发生过一次：为了让"资料里没有就别编"更严格，顺手写下
 * "也不做知识库以外的计算、写代码或闲聊"与"常识类问题同样必须回答未找到"，
 * 结果"1+1 等于几""北京是哪个国家的首都"全部被拒答，用户反馈是
 * "模型人设过于刻板，不涉及知识库的问答不自由"。
 *
 * <p>所以这组测试钉住两件事：
 * <ol>
 *   <li><b>那两句绝对化措辞不许回来</b> —— 它们是上面那次退化的直接原因；</li>
 *   <li><b>分流规则的三根支柱必须在</b> —— 允许正常回答、必须标注来源、
 *       以及"本单位细节不许用训练知识补"这条不能越的线。</li>
 * </ol>
 *
 * <p>注意：断言只针对结构化片段，不比对全文 —— 否则每改一个措辞都要重写测试，
 * 那样测试会很快被人绕过（而不是被维护）。
 *
 * @author Jiang
 */
class PromptsBoundaryTest {

    @Test
    @DisplayName("⚠️ 绝对化禁令不许回来：它们会把一切非知识库问题变成拒答")
    void shouldNotContainBlanketRefusal() {
        // 这两句是实测造成"刻板"的原句，逐字断言 —— 措辞可以被更好地表达，
        // 但"禁止一切非知识库对话"这个意思不能再出现在提示词里
        assertThat(Prompts.SYSTEM)
                .as("'不做知识库以外的…'是 v1.26 那次退化的直接原因")
                .doesNotContain("也不做知识库以外的");
        assertThat(Prompts.SYSTEM)
                .as("'常识类问题同样必须回答未找到'会让 1+1 都答不出来")
                .doesNotContain("这类问题同样必须回答");
        // 【特别注意】整段已被第 13 条取代，留着会与第 13 条直接冲突
        assertThat(Prompts.SYSTEM)
                .as("【特别注意】整段应已被第 13 条取代")
                .doesNotContain("【特别注意】");
    }

    @Test
    @DisplayName("分流规则在位：允许正常回答非知识库问题，但必须标注")
    void shouldCarryScopedAnsweringRule() {
        assertThat(Prompts.SYSTEM)
                .as("要有非知识库问题这一类，并明确可以直接回答")
                .contains("非知识库问题")
                .contains("可以直接用自己的知识正常回答");
        assertThat(Prompts.SYSTEM)
                .as("标注文案必须逐字给定，否则模型每次写法都不一样")
                .contains("以下内容不来自知识库，仅供参考：");
        assertThat(Prompts.SYSTEM)
                .as("要明确禁止推脱，否则模型仍会回退到'我不做…'")
                .contains("不要说\"我不做知识库以外的回答\"");
        assertThat(Prompts.SYSTEM)
                .as("混合问题要分开处理")
                .contains("混合问题");
    }

    @Test
    @DisplayName("★ 不能越的线在位：本单位细节不许用模型自身知识补（这是防幻觉的底线）")
    void shouldKeepInternalFactsGuardrail() {
        assertThat(Prompts.SYSTEM)
                .as("第 13 条必须显式重申这条线，否则分流规则会削弱第 2、3 条")
                .contains("绝不允许用你自己的知识补");
        assertThat(Prompts.SYSTEM)
                .as("判据要写出来，否则模型分不清'通用常识'与'内部细节'")
                .contains("只有看了这份资料才知道");
        // 第 2、3 条本身是防幻觉主干，不能被这次调整动到
        assertThat(Prompts.SYSTEM)
                .contains("一律回答\"现有资料中未找到相关信息\"")
                .contains("严禁编造任何规定、数字、流程");
    }

    @Test
    @DisplayName("自我介绍不再自我设限：不说'不做闲聊'")
    void shouldNotSelfLimitInAssistantNote() {
        assertThat(Prompts.SYSTEM)
                .as("【助手说明】里的自我设限会让元问题回答也变刻板")
                .doesNotContain("不做知识库以外的计算、写代码或闲聊");
        assertThat(Prompts.SYSTEM)
                .as("应改为'可以正常回答，但标明不来自知识库'")
                .contains("我可以正常回答，但会标明");
    }
}
