package com.itjiang.qa.config;

import java.time.Duration;

/**
 * 时长配置解析。
 *
 * <p>支持两种写法：ISO-8601（{@code PT120S}）与简洁写法（{@code 120s} / {@code 5000ms} / {@code 2m}）。
 * 简洁写法对人更友好，所以手工解析。
 *
 * <h2>为什么解析失败要回退而不是抛异常</h2>
 * <p>因为一个写错的超时值不该让整个应用起不来 —— 它的影响面只是"超时长短不合适"，
 * 而启动失败的代价是"整个服务不可用"。两害相权取其轻，回退并打 WARN 是更合理的选择。
 *
 * <h2>为什么放在 config 包而不是 common 包</h2>
 * <p>它解析的是配置值，与 {@link QaProperties}、{@link NumCtxResolver} 是同一类东西：
 * "把用户写的字符串变成程序能用的对象"。common 包放的是与业务无关的通用件
 * （错误码、响应体），性质不同。
 *
 * @author Jiang
 */
public final class DurationParser {

    /**
     * 工具类，禁止实例化。
     */
    private DurationParser() {
    }

    /**
     * 解析时长配置。
     *
     * @param raw      配置原文，可为 {@code null}
     * @param fallback 解析失败时的回退值
     * @return 解析后的时长；无法解析时返回 {@code fallback}
     */
    public static Duration parse(String raw, Duration fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().toLowerCase();
        try {
            if (value.startsWith("pt")) {
                return Duration.parse(raw.trim().toUpperCase());
            }
            if (value.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2).trim()));
            }
            if (value.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1).trim()));
            }
            if (value.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1).trim()));
            }
            return Duration.ofSeconds(Long.parseLong(value));
        } catch (Exception ex) {
            return fallback;
        }
    }
}
