package com.itjiang.qa.provider;

import com.itjiang.qa.config.QaProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 云端端点解析测试。
 *
 * <p>"留空用预设、填了以填的为准"这条规则全项目只实现一处（{@code CloudEndpointResolver}），
 * 因此它必须有测试守着 —— 一旦它错了，用户会遇到的是"配了模型名却被忽略"
 * 或"缺 Key 却显示已配置"这类极难排查的问题。
 *
 * @author Jiang
 */
class CloudEndpointResolverTest {

    /** 造一份含两家预设的配置：一家只做对话（模拟 DeepSeek），一家对话+向量都行。 */
    private static QaProperties properties() {
        QaProperties properties = new QaProperties();

        QaProperties.Cloud.Preset deepseek = new QaProperties.Cloud.Preset();
        deepseek.setLabel("DeepSeek（深度求索）");
        deepseek.setApiKeyEnv("DEEPSEEK_API_KEY");
        deepseek.setBaseUrl("https://api.deepseek.com/v1");
        deepseek.setChatModels(List.of("deepseek-flash", "deepseek-v4-pro"));
        deepseek.setEmbeddingModels(List.of());

        QaProperties.Cloud.Preset dashscope = new QaProperties.Cloud.Preset();
        dashscope.setLabel("阿里云百炼");
        dashscope.setApiKeyEnv("DASHSCOPE_API_KEY");
        dashscope.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        dashscope.setChatModels(List.of("qwen3.7-plus"));
        dashscope.setEmbeddingModels(List.of("text-embedding-v4"));
        dashscope.setEmbeddingDimensions(Map.of("text-embedding-v4", 1024));
        dashscope.setEmbeddingMaxBatch(10);

        Map<String, QaProperties.Cloud.Preset> presets = new LinkedHashMap<>();
        presets.put("deepseek", deepseek);
        presets.put("dashscope", dashscope);
        properties.getCloud().setPresets(presets);

        properties.getCloud().getChat().setProvider("deepseek");
        properties.getCloud().getEmbedding().setProvider("dashscope");
        return properties;
    }

    private static CloudEndpointResolver resolver(QaProperties properties, String... keyValues) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            environment.withProperty(keyValues[i], keyValues[i + 1]);
        }
        return new CloudEndpointResolver(properties, environment);
    }

    @Test
    @DisplayName("留空时取预设值：模型名取预设的第一个，base-url 取预设")
    void shouldFallBackToPreset() {
        CloudEndpointResolver resolver = resolver(properties(), "DEEPSEEK_API_KEY", "sk-test");

        CloudEndpointResolver.ResolvedEndpoint chat = resolver.chat();

        assertThat(chat.model()).isEqualTo("deepseek-flash");
        assertThat(chat.baseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(chat.label()).isEqualTo("DeepSeek（深度求索）");
        assertThat(chat.usable()).isTrue();
    }

    @Test
    @DisplayName("填了就以填的为准（覆盖预设）")
    void shouldPreferExplicitValue() {
        QaProperties properties = properties();
        properties.getCloud().getChat().setModel("my-own-model");
        properties.getCloud().getChat().setBaseUrl("https://my-proxy.example.com/v1");

        CloudEndpointResolver.ResolvedEndpoint chat =
                resolver(properties, "DEEPSEEK_API_KEY", "sk-test").chat();

        assertThat(chat.model()).isEqualTo("my-own-model");
        assertThat(chat.baseUrl()).isEqualTo("https://my-proxy.example.com/v1");
    }

    @Test
    @DisplayName("缺 Key 时不可用，且提示里必须写清该设哪个环境变量")
    void shouldReportMissingKeyWithActionableHint() {
        CloudEndpointResolver.ResolvedEndpoint chat = resolver(properties()).chat();

        assertThat(chat.usable()).isFalse();
        assertThat(chat.missingHint()).contains("DEEPSEEK_API_KEY");
    }

    @Test
    @DisplayName("空白值等于没填（YAML 里 `key:` 与 `key: \"\"` 都要当没填）")
    void shouldTreatBlankAsMissing() {
        QaProperties properties = properties();
        properties.getCloud().getChat().setModel("   ");
        properties.getCloud().getChat().setBaseUrl("");

        CloudEndpointResolver.ResolvedEndpoint chat =
                resolver(properties, "DEEPSEEK_API_KEY", "sk-test").chat();

        assertThat(chat.model()).isEqualTo("deepseek-flash");
        assertThat(chat.baseUrl()).isEqualTo("https://api.deepseek.com/v1");
    }

    @Test
    @DisplayName("未知 provider id：不抛异常，但要点明缺 base-url（用户多半是拼错了 id）")
    void shouldHandleUnknownProvider() {
        QaProperties properties = properties();
        properties.getCloud().getChat().setProvider("deepsek");   // 少个 e

        CloudEndpointResolver.ResolvedEndpoint chat =
                resolver(properties, "DEEPSEEK_API_KEY", "sk-test").chat();

        assertThat(chat.usable()).isFalse();
        assertThat(chat.missingHint()).contains("base-url");
    }

    @Test
    @DisplayName("维度：显式配置优先，否则查预设的模型→维度表")
    void shouldResolveDimension() {
        QaProperties properties = properties();
        CloudEndpointResolver resolver = resolver(properties, "DASHSCOPE_API_KEY", "sk-test");

        assertThat(resolver.embedding().dimension()).isEqualTo(1024);

        properties.getCloud().getEmbedding().setDimension(768);
        assertThat(resolver(properties, "DASHSCOPE_API_KEY", "sk-test").embedding().dimension())
                .isEqualTo(768);
    }

    @Test
    @DisplayName("批大小取「配置值」与「厂商上限」的较小者（超限是 400，不是慢一点）")
    void shouldCapBatchSizeByVendorLimit() {
        QaProperties properties = properties();
        properties.getCloud().getEmbedding().setBatchSize(16);

        assertThat(resolver(properties).embeddingBatchSize()).isEqualTo(10);

        properties.getCloud().getEmbedding().setBatchSize(4);
        assertThat(resolver(properties).embeddingBatchSize()).isEqualTo(4);
    }

    @Test
    @DisplayName("预设清单：只回 Key 的布尔状态，绝不回显内容")
    void presetViewsShouldNotLeakKey() {
        CloudEndpointResolver resolver = resolver(properties(),
                "DEEPSEEK_API_KEY", "sk-secret-value");

        List<CloudEndpointResolver.PresetView> views = resolver.presetViews();
        CloudEndpointResolver.PresetView deepseek = views.stream()
                .filter(v -> v.id().equals("deepseek")).findFirst().orElseThrow();
        CloudEndpointResolver.PresetView dashscope = views.stream()
                .filter(v -> v.id().equals("dashscope")).findFirst().orElseThrow();

        assertThat(deepseek.apiKeyConfigured()).isTrue();
        assertThat(dashscope.apiKeyConfigured()).isFalse();
        assertThat(deepseek.apiKeyEnv()).isEqualTo("DEEPSEEK_API_KEY");
        // 整个视图里不该出现密钥本身
        assertThat(views.toString()).doesNotContain("sk-secret-value");
    }
}
