package com.itjiang.qa.model.enums;

/**
 * 文档入库状态（表 {@code qa_document.status}）。
 *
 * <p>用途不只是"显示一个标签"，它是<b>失败留痕</b>的载体：
 * 用户上传一个扫描版 PDF 后检索不到内容时，唯一能解释原因的地方就是
 * {@code status=FAILED} + {@code errorMessage}。没有它，用户只会觉得"这系统不好用"。
 *
 * @author Jiang
 */
public enum DocStatus {

    /** 已接收文件、写入了元数据，但尚未开始处理。 */
    PENDING("PENDING"),

    /** 正在解析、分块、向量化。 */
    PARSING("PARSING"),

    /** 已成功入库，可以检索。 */
    INDEXED("INDEXED"),

    /** 处理失败，失败原因在 {@code error_message} 列。 */
    FAILED("FAILED");

    private final String value;

    DocStatus(String value) {
        this.value = value;
    }

    /**
     * @return 数据库中存储的字面值
     */
    public String getValue() {
        return value;
    }

    /**
     * 解析数据库中的值。
     *
     * <p>无法识别时返回 {@link #FAILED} 而不是抛异常 ——
     * 一条状态异常的记录不应该让整个文档列表接口挂掉。
     *
     * @param value 数据库值
     * @return 对应状态，无法识别时返回 {@link #FAILED}
     */
    public static DocStatus from(String value) {
        if (value != null) {
            for (DocStatus status : values()) {
                if (status.value.equalsIgnoreCase(value.trim())) {
                    return status;
                }
            }
        }
        return FAILED;
    }
}
