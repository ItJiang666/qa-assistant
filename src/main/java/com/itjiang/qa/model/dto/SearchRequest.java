package com.itjiang.qa.model.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 检索试跑请求：只做向量检索，不让模型生成。
 *
 * <h2>为什么需要它</h2>
 * <p>调检索参数时，如果每次都走完整问答链路，就同时混进了"模型这一轮答得好不好"的变量，
 * 而且每次都要花一次生成的钱与时间。这个接口把检索单独拎出来：
 * <b>一次请求只做"问题向量化 + 向量库搜索"</b>，因此可以拿它反复跑评测集
 * （十几个探针一秒出结果、几乎零成本），把"召回"这件事量化之后再调参。
 *
 * <p>注意它<b>不注入空资料占位符</b>，也不做任何防幻觉处理 —— 它返回的就是向量库里的原始命中，
 * 这样评测看到的才是检索的真实水平。
 *
 * @param query    查询文本
 * @param topK     返回条数上限；上限刻意放到 20，评测要能测出"放大召回能不能覆盖正确答案"
 * @param minScore 相似度阈值
 * @author Jiang
 */
public record SearchRequest(
        @NotBlank(message = "query 不能为空")
        @Size(max = 2000, message = "query 过长")
        String query,

        @Min(value = 1, message = "topK 至少为 1")
        @Max(value = 20, message = "topK 最多为 20")
        Integer topK,

        @DecimalMin(value = "0.0", message = "minScore 不能小于 0")
        @DecimalMax(value = "1.0", message = "minScore 不能大于 1")
        Double minScore,

        /**
         * 是否走查询改写（多路检索 + 合并）。
         *
         * <p>默认 {@code false}：这样试跑接口保持"只做向量检索、零模型调用"的轻量特性，
         * 评测基线可以反复跑。要测改写带来的召回增益时传 {@code true} ——
         * 此时与线上问答走的是<b>同一套</b>改写与合并逻辑。
         */
        Boolean expand) {

    /**
     * 取生效的 topK。
     *
     * <p>默认值由调用方传入（来自 {@code qa.retrieval.top-k}），**不在本类里写死** ——
     * 否则这个试跑接口报出来的参数会与实际问答用的参数不一致，
     * 调参时就会被自己的工具误导。
     *
     * @param configuredDefault 配置里的默认值
     * @return 生效的 topK
     */
    public int topKOrDefault(int configuredDefault) {
        return topK == null ? configuredDefault : topK;
    }

    /**
     * 取生效的 minScore。
     *
     * @param configuredDefault 配置里的默认值
     * @return 生效的 minScore
     */
    public double minScoreOrDefault(double configuredDefault) {
        return minScore == null ? configuredDefault : minScore;
    }

    /**
     * @return 是否启用查询改写；未指定时为 {@code false}
     */
    public boolean expandOrDefault() {
        return Boolean.TRUE.equals(expand);
    }
}
