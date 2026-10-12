package com.itjiang.qa.service;

import com.itjiang.qa.mapper.DocumentMapper;
import com.itjiang.qa.provider.RuntimeModelRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 元问题短路匹配的回归测试。
 *
 * <p><b>这组测试的重点不是"能不能命中"，而是"会不会误伤"</b>：
 * 短路回答绕过了检索与模型，一旦误判，一个正经的知识库问题会被替换成固定文案 ——
 * 而用户不会怀疑是匹配规则，只会认为检索坏了。所以下面刻意留了一半用例
 * 专门验证"不该命中的没命中"。
 *
 * @author Jiang
 */
class MetaQuestionServiceTest {

    private RuntimeModelRegistry modelRegistry;

    private MetaQuestionService service;

    @BeforeEach
    void setUp() {
        // reply() 会读模型名/模式/向量模型来拼文案，裸 mock 会返回 null 并在拼接时 NPE。
        // 这里给最小可用的桩值：本组测试断言的是"边界说明有没有写进去"，
        // 不关心具体模型名，但必须让拼接这条路走得通。
        modelRegistry = mock(RuntimeModelRegistry.class);
        when(modelRegistry.chatModelName()).thenReturn("test-model");
        when(modelRegistry.chatMode()).thenReturn(RuntimeModeService.Mode.LOCAL);
        when(modelRegistry.embeddingModelName()).thenReturn("test-embed");

        service = new MetaQuestionService(
                modelRegistry,
                mock(ThinkingModeService.class),
                mock(DocumentMapper.class));
    }

    @Test
    @DisplayName("典型元问题与冒烟口令都能命中")
    void shouldMatchMetaQuestions() {
        assertThat(service.match("你是谁")).isEqualTo("identity");
        assertThat(service.match("介绍一下你自己")).isEqualTo("identity");
        assertThat(service.match("你是什么模型")).isEqualTo("model");
        assertThat(service.match("你用的哪个模型")).isEqualTo("model");
        assertThat(service.match("你能做什么")).isEqualTo("capability");
        assertThat(service.match("你怎么记住我")).isEqualTo("memory");
        assertThat(service.match("你的资料从哪来")).isEqualTo("datasource");
        assertThat(service.match("测试")).isEqualTo("smoke");
    }

    @Test
    @DisplayName("大小写、空格与句尾标点不影响命中")
    void shouldNormalizeBeforeMatching() {
        assertThat(service.match("你是谁？")).isEqualTo("identity");
        assertThat(service.match("你 是 谁 ！")).isEqualTo("identity");
        assertThat(service.match("  TEST  ")).isEqualTo("smoke");
        assertThat(service.match("你是什么模型。。。")).isEqualTo("model");
    }

    @Test
    @DisplayName("⚠️ 知识库问题绝不能被劫持（短路回答绕过了检索，误判比答错更糟）")
    void shouldNotHijackRealQuestions() {
        // 这些句子里含有匹配词，但都是正经问题 —— 只做"完全相等"匹配正是为了它们
        assertThat(service.match("测试环境怎么部署")).isNull();
        assertThat(service.match("测试用例要写多少条")).isNull();
        assertThat(service.match("你是谁写的这份文档")).isNull();
        assertThat(service.match("支持哪些格式的文件上传失败怎么办")).isNull();
        assertThat(service.match("你好，请问工单超时怎么处理")).isNull();
        assertThat(service.match("长期记忆存在哪个表里，怎么清理")).isNull();
        assertThat(service.match("知识库在哪台机器上部署的")).isNull();
    }

    @Test
    @DisplayName("空输入与纯标点不算命中")
    void shouldIgnoreBlankInput() {
        assertThat(service.match(null)).isNull();
        assertThat(service.match("")).isNull();
        assertThat(service.match("   ")).isNull();
        assertThat(service.match("？")).isNull();
    }

    @Test
    @DisplayName("自我介绍要说明'知识库外的问题也能答'，不能自我设限（决策 45）")
    void shouldDescribeScopeWithoutSelfLimiting() {
        String identity = service.reply("identity");

        // 旧文案写的是"只依据你上传的文档回答…不拿模型自身知识补充"，
        // 单看没错，但它把"能不能答知识库以外的问题"这件事含混掉了，
        // 实测用户据此认为"这个助手只会查资料、别的都不会"。
        assertThat(identity)
                .as("要明确知识库以外的问题可以正常回答")
                .contains("知识库以外的问题")
                .contains("可以正常回答");
        assertThat(identity)
                .as("要说明这类回答会怎么标注，否则用户不知道那句标注是系统行为")
                .contains("不来自知识库");

        // 能力询问同样不该说成"只能查资料"
        String capability = service.reply("capability");
        assertThat(capability)
                .as("'你能做什么'的回答里要提到通用问答也可以")
                .contains("常识")
                .contains("不来自知识库");
    }
}
