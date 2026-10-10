package com.itjiang.qa.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 手动新增记忆请求（对应前端「记住这条」按钮）。
 *
 * <h2>为什么要有 memoryType，而且默认自动判定</h2>
 * <p>这条字段是<b>一个真实故障逼出来的</b>：用户点了「记住这条」，内容是
 * "每次回答结束后加上一句（回答完毕）"，但它被一律存成 {@code CONCLUSION}，
 * 注入时的措辞是"【已确认的结论，可直接使用】"—— 那是<b>让人可以引用</b>的语气，
 * 不是"必须照做"。结果模型一次都没加那句话，用户看到的就是"长期记忆根本没用"。
 *
 * <p>根因不在措辞，在<b>类型</b>：只有 {@code PREFERENCE} 会注入
 * "【偏好｜请在本次回答中严格遵守】"，也只有它会触发铁律第 5 条。
 * 所以这里两手都要有：
 * <ol>
 *   <li>不传类型时按措辞<b>自动判定</b>（含"请/必须/每次/不要…"这类祈使词 → 偏好），
 *       让默认行为就是对的；</li>
 *   <li>同时允许显式指定，界面上也能改 —— 自动判定一定会有判错的时候。</li>
 * </ol>
 *
 * @param content    记忆内容
 * @param importance 重要度 1~5，可为空（默认 4）
 * @param memoryType 记忆类型：{@code PREFERENCE} / {@code FACT} / {@code CONCLUSION}；
 *                   留空则自动判定
 * @author Jiang
 */
public record MemoryCreateRequest(
        @NotBlank(message = "记忆内容不能为空")
        @Size(max = 500, message = "记忆内容不能超过 500 字")
        String content,

        Integer importance,

        @Pattern(regexp = "PREFERENCE|FACT|CONCLUSION",
                message = "memoryType 只能是 PREFERENCE / FACT / CONCLUSION")
        String memoryType
) {
}
