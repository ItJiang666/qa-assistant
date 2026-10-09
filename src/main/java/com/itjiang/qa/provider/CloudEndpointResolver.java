package com.itjiang.qa.provider;

import com.itjiang.qa.config.QaProperties;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云端端点解析器：把「厂商预设 + 显式覆盖 + 环境变量」合成一个可直接使用的端点。
 *
 * <h2>为什么要有这一层，而不是在装配模型时直接读配置</h2>
 * <p>因为同一件事有四个消费方：装配对话模型、装配向量模型、系统状态接口、
 * 界面上的连通性自检。如果每个地方各写一遍"先看有没有填、没填就读预设、
 * 再读环境变量"，四处迟早会不一致 —— 最典型的后果是<b>界面上显示"已配置"、
 * 实际调用却因为读到空 Key 而 401</b>。所以规则只在这里实现一次。
 *
 * <h2>取值规则（唯一出处）</h2>
 * <ol>
 *   <li><b>base-url / model</b>：配置里填了就用填的，留空用预设的第一个候选</li>
 *   <li><b>api-key</b>：配置里直接填了就用它（不推荐，等于把密钥写进配置文件）；
 *       否则读预设声明的环境变量（{@code api-key-env}）</li>
 *   <li><b>dimension</b>：配置里填了就用填的，留空查预设的「模型 → 维度」表</li>
 * </ol>
 * <p>"留空"的判定统一用 {@link #isBlank}：null、空串、纯空白都算没填 ——
 * 这一点很重要，YAML 里写 {@code base-url:} 是 null，写 {@code base-url: ""} 是空串，
 * 用户不该为这两种写法的区别承担后果。
 *
 * @author Jiang
 */
public class CloudEndpointResolver {

    private final QaProperties properties;
    private final Environment environment;

    public CloudEndpointResolver(QaProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    /**
     * 组装对话请求要透传给厂商的额外参数。
     *
     * <p>合并顺序（后者覆盖前者）：
     * <ol>
     *   <li><b>预设的"关思考"参数</b> —— 仅当 {@code qa.cloud.chat.thinking=false}。
     *       关掉思考对本项目是默认且必要的：DeepSeek 当前模型默认开启思考，
     *       而本地实测思考模式要慢 16.5 倍（决策 22）</li>
     *   <li><b>用户显式配置的 custom-parameters</b> —— 永远最后合并，
     *       这样"厂商上了新参数"或"预设写错了"都能在不改代码的前提下纠正</li>
     * </ol>
     *
     * @return 待合并进请求体的参数；无则为空 Map
     */
    public Map<String, Object> chatExtraParameters() {
        QaProperties.Cloud.Chat cfg = properties.getCloud().getChat();
        Map<String, Object> params = new LinkedHashMap<>();
        if (!cfg.isThinking()) {
            QaProperties.Cloud.Preset preset = preset(cfg.getProvider());
            if (preset != null) {
                params.putAll(preset.getThinkingOffParams());
            }
        }
        if (cfg.getCustomParameters() != null) {
            params.putAll(cfg.getCustomParameters());
        }
        return params;
    }

    /**
     * 解析对话端点。
     *
     * @return 解析结果
     */
    public ResolvedEndpoint chat() {
        QaProperties.Cloud.Chat cfg = properties.getCloud().getChat();
        QaProperties.Cloud.Preset preset = preset(cfg.getProvider());
        String model = firstNonBlank(cfg.getModel(), firstOf(preset == null ? null : preset.getChatModels()));
        return new ResolvedEndpoint(
                cfg.getProvider(),
                preset == null ? null : preset.getLabel(),
                firstNonBlank(cfg.getBaseUrl(), preset == null ? null : preset.getBaseUrl()),
                model,
                resolveApiKey(cfg.getApiKey(), preset),
                preset == null ? null : preset.getApiKeyEnv(),
                null,
                preset == null ? null : preset.getNotes());
    }

    /**
     * 解析向量端点。
     *
     * @return 解析结果
     */
    public ResolvedEndpoint embedding() {
        QaProperties.Cloud.Embedding cfg = properties.getCloud().getEmbedding();
        QaProperties.Cloud.Preset preset = preset(cfg.getProvider());
        String model = firstNonBlank(cfg.getModel(), firstOf(preset == null ? null : preset.getEmbeddingModels()));
        Integer dimension = cfg.getDimension() != null
                ? cfg.getDimension()
                : lookupDimension(preset, model);
        return new ResolvedEndpoint(
                cfg.getProvider(),
                preset == null ? null : preset.getLabel(),
                firstNonBlank(cfg.getBaseUrl(), preset == null ? null : preset.getBaseUrl()),
                model,
                resolveApiKey(cfg.getApiKey(), preset),
                preset == null ? null : preset.getApiKeyEnv(),
                dimension,
                preset == null ? null : preset.getNotes());
    }

    /**
     * 当前配置的对话是否指向一个真实厂商。
     *
     * <p><b>为什么"没填 Key"要单独判断，而不是让它 401 就算了：</b>
     * 云端模式的开启是用户在界面上点的，点完立刻要用。Key 缺失属于"点之前就该拦住"
     * 的问题，必须在切换时给出一句人话，而不是让第一次提问以 401 收场。
     *
     * @return 对话端点是否可用于发起请求
     */
    public boolean chatReady() {
        ResolvedEndpoint endpoint = chat();
        return endpoint.usable();
    }

    /**
     * 取全部厂商预设（界面下拉框用）。
     *
     * <p>返回的是视图对象而不是配置对象：界面需要的是"能不能选、Key 配没配"，
     * 直接暴露配置对象会把内部结构泄露到接口层。
     *
     * @return 预设视图列表，顺序与 YAML 书写顺序一致
     */
    public List<PresetView> presetViews() {
        List<PresetView> views = new ArrayList<>();
        for (Map.Entry<String, QaProperties.Cloud.Preset> entry : properties.getCloud().getPresets().entrySet()) {
            QaProperties.Cloud.Preset preset = entry.getValue();
            String envName = preset.getApiKeyEnv();
            views.add(new PresetView(
                    entry.getKey(),
                    preset.getLabel() == null ? entry.getKey() : preset.getLabel(),
                    preset.getBaseUrl(),
                    List.copyOf(preset.getChatModels()),
                    List.copyOf(preset.getEmbeddingModels()),
                    new LinkedHashMap<>(preset.getEmbeddingDimensions()),
                    preset.getNotes(),
                    envName,
                    !isBlank(envName) && !isBlank(environment.getProperty(envName))));
        }
        return views;
    }

    /**
     * 按 id 取预设。
     *
     * @param providerId 预设 id，可为空
     * @return 预设；未配置或 id 不存在时返回 {@code null}
     */
    private QaProperties.Cloud.Preset preset(String providerId) {
        if (isBlank(providerId)) {
            return null;
        }
        return properties.getCloud().getPresets().get(providerId.trim());
    }

    /**
     * 解析 API Key。
     *
     * @param explicit 配置里直接填的 Key，可为空
     * @param preset   厂商预设，可为空
     * @return Key；都没有时返回 {@code null}
     */
    private String resolveApiKey(String explicit, QaProperties.Cloud.Preset preset) {
        if (!isBlank(explicit)) {
            return explicit.trim();
        }
        if (preset == null || isBlank(preset.getApiKeyEnv())) {
            return null;
        }
        String fromEnv = environment.getProperty(preset.getApiKeyEnv().trim());
        return isBlank(fromEnv) ? null : fromEnv.trim();
    }

    /**
     * 查预设里的向量维度表。
     *
     * @param preset 厂商预设，可为空
     * @param model  模型名，可为空
     * @return 维度；查不到返回 {@code null}
     */
    private Integer lookupDimension(QaProperties.Cloud.Preset preset, String model) {
        if (preset == null || isBlank(model)) {
            return null;
        }
        return preset.getEmbeddingDimensions().get(model);
    }

    /**
     * 取第一个非空值。
     *
     * @param candidates 候选值
     * @return 第一个非空值；全为空时返回 {@code null}
     */
    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (!isBlank(candidate)) {
                return candidate.trim();
            }
        }
        return null;
    }

    /**
     * 取列表第一项。
     *
     * @param values 列表，可为 {@code null}
     * @return 第一项；列表为空时返回 {@code null}
     */
    private String firstOf(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /**
     * 判定"没填"。
     *
     * @param value 待判定值
     * @return null、空串、纯空白都算没填
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 解析后的端点。
     *
     * <p>{@link #usable()} 同时要求 base-url、model、api-key 三者齐备 —— 少任何一样，
     * 请求都必然失败，不如提前说清楚。
     *
     * @param providerId    厂商预设 id
     * @param label         厂商显示名（预设未命中时为 {@code null}）
     * @param baseUrl       端点根地址
     * @param model         模型名
     * @param apiKey        API Key（不对外回显，只用于判断是否存在与发起请求）
     * @param apiKeyEnv    Key 所在的环境变量名
     * @param dimension    向量维度（仅向量端点有值）
     * @param notes        厂商注意事项
     */
    public record ResolvedEndpoint(
            String providerId,
            String label,
            String baseUrl,
            String model,
            String apiKey,
            String apiKeyEnv,
            Integer dimension,
            String notes) {

        /**
         * 是否具备发起请求的全部条件。
         *
         * @return 齐备返回 {@code true}
         */
        public boolean usable() {
            return baseUrl != null && model != null && apiKey != null;
        }

        /**
         * 还缺什么，用一句人话描述。
         *
         * <p>缺 Key 时要把环境变量的名字写出来 —— "请设置 DEEPSEEK_API_KEY"
         * 比"密钥未配置"有用得多。
         *
         * @return 缺失项描述；齐备时返回 {@code null}
         */
        public String missingHint() {
            List<String> missing = new ArrayList<>();
            if (baseUrl == null) {
                missing.add("base-url（厂商预设里也没有，请检查 qa.cloud.presets）");
            }
            if (model == null) {
                missing.add("model（模型名）");
            }
            if (apiKey == null) {
                missing.add(apiKeyEnv == null
                        ? "api-key（预设未声明 api-key-env，请显式配置）"
                        : "环境变量 " + apiKeyEnv);
            }
            return missing.isEmpty() ? null : String.join("、", missing);
        }
    }

    /**
     * 厂商预设视图（界面下拉框用）。
     *
     * @param id                  预设 id
     * @param label               显示名
     * @param baseUrl             端点根地址
     * @param chatModels          可用对话模型
     * @param embeddingModels     可用向量模型
     * @param embeddingDimensions 向量模型 → 维度
     * @param notes               注意事项
     * @param apiKeyEnv           Key 所在的环境变量名
     * @param apiKeyConfigured    该环境变量当前是否已有值（只回布尔，绝不回显内容）
     */
    public record PresetView(
            String id,
            String label,
            String baseUrl,
            List<String> chatModels,
            List<String> embeddingModels,
            Map<String, Integer> embeddingDimensions,
            String notes,
            String apiKeyEnv,
            boolean apiKeyConfigured) {
    }
}
