package com.itjiang.qa.model.dto;

import java.util.List;

/**
 * 云端连通性自检结果。
 *
 * <p><b>为什么要专门做这个接口：</b>云端配置错了有三类完全不同的表现 ——
 * Key 没填（本地就能看出来）、Key 填错（401）、网络不通（超时）。
 * 用户看到的现象却都是"提问失败"，而这三类的处置动作完全不同
 * （填 Key / 换 Key / 查网络与代理）。自检就是把这三类当场分开。
 *
 * <p>刻意区分 {@code reachable} 与 {@code ok}：
 * 收到 401 说明<b>网络是通的</b>，只是 Key 不认 —— 这跟"连不上"是两回事，
 * 报成同一句话会让用户白查半天网络。
 *
 * @param items 各端点的检查结果
 * @author Jiang
 */
public record CloudCheckResponse(List<Item> items) {

    /**
     * 单个端点的检查结果。
     *
     * @param target    端点用途：{@code chat} / {@code embedding}
     * @param label     厂商显示名
     * @param url       实际请求的地址（不含 Key）
     * @param model     该端点配置的模型名
     * @param reachable 是否收到了 HTTP 响应（收到即说明网络与域名可达）
     * @param status    HTTP 状态码；未收到响应时为 {@code null}
     * @param latencyMs 耗时（毫秒）
     * @param ok        是否完全可用（通常以 200 为判据）
     * @param message   给人看的一句话结论
     */
    public record Item(
            String target,
            String label,
            String url,
            String model,
            boolean reachable,
            Integer status,
            long latencyMs,
            boolean ok,
            String message) {
    }
}
