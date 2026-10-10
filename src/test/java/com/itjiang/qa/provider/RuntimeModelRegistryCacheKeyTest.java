package com.itjiang.qa.provider;

import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.service.RuntimeModeService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型缓存键的回归测试。
 *
 * <p><b>它防的是一类很难发现的故障</b>：运行中改了 Key 或模型名之后，
 * 界面（{@code /status}）会立刻显示新值，而实际请求仍由<b>装配时固定下来的旧实例</b>
 * 发出 —— 用户看到的是"改好了"，实际没生效。把云端配置快照纳入缓存键之后，
 * 配置一变键就变、缓存自然未命中，于是自动重装实例。
 *
 * <p>这里只测"键会不会变"这一件事：它是整条自愈链路的第一环，
 * 也是最容易被后人"顺手简化掉"的一环（比如把键改回只用模式名）。
 *
 * @author Jiang
 */
class RuntimeModelRegistryCacheKeyTest {

    private static QaProperties properties(String model, String provider) {
        QaProperties properties = new QaProperties();
        // data-dir 必须给值：配置快照里含数据目录（它是"改了必须重启"的配置之一），
        // 留空会在推导路径时 NPE。这里用与 application.yml 默认值一致的写法。
        properties.setDataDir(System.getProperty("user.home") + "/.qa-assistant");
        QaProperties.Cloud.Preset preset = new QaProperties.Cloud.Preset();
        preset.setLabel("DeepSeek（深度求索）");
        preset.setApiKeyEnv("DEEPSEEK_API_KEY");
        preset.setBaseUrl("https://api.deepseek.com/v1");
        preset.setChatModels(List.of("deepseek-flash", "deepseek-v4-pro"));
        preset.setEmbeddingModels(List.of());
        properties.getCloud().setPresets(new LinkedHashMap<>(Map.of("deepseek", preset)));
        properties.getCloud().getChat().setProvider(provider);
        properties.getCloud().getChat().setModel(model);
        return properties;
    }

    /**
     * 造一个真实的注册表（依赖用桩替代）。
     *
     * <p>向量模型用一个 mock：注册表在构造时就要装配它，而本测试不关心它是什么，
     * 只关心缓存键怎么算。
     *
     * @param model      对话模型名
     * @param keyValues  环境变量键值对（模拟 .env 里的 Key）
     * @return 注册表
     */
    private static RuntimeModelRegistry registryFor(String model, String... keyValues) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            environment.withProperty(keyValues[i], keyValues[i + 1]);
        }
        CloudEndpointResolver resolver =
                new CloudEndpointResolver(properties(model, "deepseek"), environment);

        // 注册表构造时会装配一次向量模型（启动即定），这里只给它一个桩：
        // 本测试不关心向量模型是什么，只关心对话模型的缓存键怎么算。
        EmbeddingModel embedding = mock(EmbeddingModel.class);
        LocalModelProvider local = mock(LocalModelProvider.class);
        CloudModelProvider cloud = mock(CloudModelProvider.class);
        when(local.embeddingModel()).thenReturn(embedding);
        when(cloud.embeddingModel()).thenReturn(embedding);

        RuntimeModeService modeService = mock(RuntimeModeService.class);
        when(modeService.configured()).thenReturn(RuntimeModeService.Mode.CLOUD);

        return new RuntimeModelRegistry(local, cloud, modeService, resolver, null);
    }

    private static String cloudKey(String model, String... keyValues) {
        return registryFor(model, keyValues).cacheKey(RuntimeModeService.Mode.CLOUD);
    }

    @Test
    @DisplayName("换模型名 → 缓存键必须变（否则界面显示新模型、实际还用旧的）")
    void shouldChangeKeyWhenModelChanges() {
        String before = cloudKey("deepseek-flash", "DEEPSEEK_API_KEY", "sk-aaaa1111");
        String after = cloudKey("deepseek-v4-pro", "DEEPSEEK_API_KEY", "sk-aaaa1111");

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @DisplayName("换 Key → 缓存键必须变（否则新 Key 永远不生效）")
    void shouldChangeKeyWhenApiKeyChanges() {
        String before = cloudKey("deepseek-flash", "DEEPSEEK_API_KEY", "sk-aaaa1111");
        String after = cloudKey("deepseek-flash", "DEEPSEEK_API_KEY", "sk-bbbb2222");

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @DisplayName("什么都不改 → 缓存键必须稳定（否则每问一次都重建 HTTP 客户端）")
    void shouldKeepKeyStableWhenNothingChanges() {
        String first = cloudKey("deepseek-flash", "DEEPSEEK_API_KEY", "sk-aaaa1111");
        String second = cloudKey("deepseek-flash", "DEEPSEEK_API_KEY", "sk-aaaa1111");

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("缓存键里绝不能出现明文 Key（它会进入日志与排查现场）")
    void shouldNotLeakPlainKey() {
        String key = cloudKey("deepseek-flash", "DEEPSEEK_API_KEY", "sk-super-secret-9876");

        assertThat(key).doesNotContain("sk-super-secret-9876");
        assertThat(key).contains("9876");   // 末四位保留，用于核对"用的是哪一把"
    }

    @Test
    @DisplayName("本地模式不拼配置快照：键就是模式名")
    void localModeKeyShouldStaySimple() {
        RuntimeModelRegistry registry = registryFor("deepseek-flash",
                "DEEPSEEK_API_KEY", "sk-aaaa1111");

        assertThat(registry.cacheKey(RuntimeModeService.Mode.LOCAL)).isEqualTo("local");
    }
}
