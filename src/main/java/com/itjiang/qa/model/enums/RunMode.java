package com.itjiang.qa.model.enums;

/**
 * 运行模式：本地模型 / 云端模型（开发文档 4.4）。
 *
 * <p>由配置项 {@code qa.mode} 决定，<b>不是</b> Spring profile ——
 * profile 管数据源（h2 / mysql），本枚举管模型来源，两者是正交的两条轴。
 *
 * @author Jiang
 */
public enum RunMode {

    /** 本地模式：模型跑在本机 Ollama，断网可用，文档不出内网。 */
    LOCAL("local"),

    /** 云端模式：通过 HTTP 调用云厂商 API，需要联网与 API Key。 */
    CLOUD("cloud");

    private final String value;

    RunMode(String value) {
        this.value = value;
    }

    /**
     * @return 配置文件中使用的字面值
     */
    public String getValue() {
        return value;
    }

    /**
     * 解析配置值，大小写不敏感。
     *
     * <p>无法识别时回退到 {@link #LOCAL}：本地模式不依赖网络和密钥，
     * 是最安全的兜底选择 —— 配置写错时"至少还能用"，而不是直接起不来。
     *
     * @param value 配置值，如 {@code local} / {@code cloud}
     * @return 对应的运行模式，无法识别时返回 {@link #LOCAL}
     */
    public static RunMode from(String value) {
        if (value == null) {
            return LOCAL;
        }
        for (RunMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return LOCAL;
    }
}
