package com.itjiang.qa.model.enums;

/**
 * 长期记忆类型（开发文档 4.6.3）。
 *
 * <p>只存三类<b>关于用户的、长期有效的判断依据</b>。分类不是为了好看 ——
 * 它决定了注入 Prompt 时怎么标注，模型才知道该怎么用：
 * 偏好用来调整语气详略，事实用来理解背景，结论用来避免重复确认。
 *
 * @author Jiang
 */
public enum MemoryType {

    /** 偏好：用户希望怎么被回答，例如"回答要简洁"。 */
    PREFERENCE("PREFERENCE", "偏好"),

    /** 事实：关于用户的稳定信息，例如"我是运维组的"。 */
    FACT("FACT", "事实"),

    /** 结论：用户认可过的答案，例如"我们公司年假是 15 天"。 */
    CONCLUSION("CONCLUSION", "结论");

    private final String value;
    private final String label;

    MemoryType(String value, String label) {
        this.value = value;
        this.label = label;
    }

    /**
     * @return 数据库中存储的字面值
     */
    public String getValue() {
        return value;
    }

    /**
     * @return 注入 Prompt 时使用的中文标签
     */
    public String getLabel() {
        return label;
    }

    /**
     * 解析数据库值。
     *
     * <p>无法识别时归为 {@link #FACT}：这是三类里最中性的一种，
     * 即使分类错了也只是注入时标签不准，不会导致记忆被丢弃。
     *
     * @param value 数据库值
     * @return 对应类型
     */
    public static MemoryType from(String value) {
        if (value != null) {
            for (MemoryType type : values()) {
                if (type.value.equalsIgnoreCase(value.trim())) {
                    return type;
                }
            }
        }
        return FACT;
    }
}
