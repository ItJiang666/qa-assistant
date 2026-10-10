package com.itjiang.qa.service;

import com.itjiang.qa.model.dto.CloudCheckResponse;
import com.itjiang.qa.provider.CloudEndpointResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 云端连通性自检。
 *
 * <h2>探哪个地址</h2>
 * 探 {@code GET {base-url}/models} —— OpenAI 兼容协议的模型列表端点，
 * 国内厂商基本都实现了它。它的价值在于：<b>一次请求就能同时验证域名解析、TLS、
 * 网络可达性与 Key 是否有效</b>，而不消耗任何生成额度。
 *
 * <h2>三类失败必须分开报</h2>
 * <ol>
 *   <li><b>配置不全</b>（缺 base-url / 模型名 / Key）—— 不用发请求就能判定，
 *       直接告诉用户缺哪一项、去哪个环境变量补</li>
 *   <li><b>收到 401/403</b> —— 网络是通的，Key 不被接受。此时最该提醒的是
 *       "百炼的 Key 与地域绑定"这类坑，而不是让用户去查网络</li>
 *   <li><b>超时 / IO 异常</b> —— 网络或代理问题；也可能是公司网络需要代理</li>
 * </ol>
 *
 * <p>另外，探活请求<b>绝不能把 Key 写进日志或响应</b>：这里只在请求头里用它，
 * 返回体与日志都只出现 URL 与状态码。
 *
 * @author Jiang
 */
@Service
public class CloudConnectivityService {

    private static final Logger log = LoggerFactory.getLogger(CloudConnectivityService.class);

    /** 自检超时。比正式调用短：自检要的是"快速给结论"，不是等到天荒地老。 */
    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(8);

    private final CloudEndpointResolver resolver;
    private final HttpClient httpClient;

    /**
     * 最近一次自检的结论。
     *
     * <h2>为什么要把结论留在内存里</h2>
     * <p>自检是"用户点一下才发生"的：点完看到"两个端点都连通"，然后关掉弹窗，
     * 这个结论就消失了。而它恰恰是回答"我刚换的 Key 到底生效了没有"的<b>唯一证据</b>——
     * 换 Key 之后页面上其他任何地方都看不出差别（Key 相同厂商时连厂商名都不变）。
     * 所以留一份在内存里，随 {@code /status} 一起回给界面。
     *
     * <p>同时记下<b>自检时的配置快照</b>：若之后配置又变了，界面就能说
     * "这个结论已经过期，请重新自检"，而不是让用户拿着旧结论下判断。
     */
    private final AtomicReference<LastCheck> lastCheck = new AtomicReference<>();

    public CloudConnectivityService(CloudEndpointResolver resolver) {
        this.resolver = resolver;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CHECK_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 检查对话与向量两个端点，并记住本次结论。
     *
     * @return 自检结果
     */
    public CloudCheckResponse checkAll() {
        List<CloudCheckResponse.Item> items = new ArrayList<>();
        items.add(check("chat", resolver.chat()));
        items.add(check("embedding", resolver.embedding()));
        CloudCheckResponse response = new CloudCheckResponse(items);
        lastCheck.set(new LastCheck(
                OffsetDateTime.now().toString(),
                resolver.configView(),
                items.stream().allMatch(CloudCheckResponse.Item::ok),
                response));
        return response;
    }

    /**
     * 最近一次自检的结论。
     *
     * @return 最近结论；从未自检时返回 {@code null}
     */
    public LastCheck lastCheck() {
        return lastCheck.get();
    }

    /**
     * 最近一次自检的结论及其"新鲜度"信息。
     *
     * @param checkedAt        自检时刻（ISO-8601）
     * @param configAtCheck    自检时的云端配置快照，用于判断结论是否已过期
     * @param allOk            是否全部通过
     * @param result           原始自检结果（与原接口返回体完全一致）
     */
    public record LastCheck(
            String checkedAt,
            CloudEndpointResolver.CloudConfigView configAtCheck,
            boolean allOk,
            CloudCheckResponse result) {

        /**
         * 结论相对当前配置是否已过期。
         *
         * @param current 当前配置快照
         * @return 配置已变（结论不再可信）返回 {@code true}
         */
        public boolean stale(CloudEndpointResolver.CloudConfigView current) {
            return !configAtCheck.sameAs(current);
        }
    }

    /**
     * 检查单个端点。
     *
     * @param target   用途标识
     * @param endpoint 解析后的端点
     * @return 检查结果
     */
    private CloudCheckResponse.Item check(String target, CloudEndpointResolver.ResolvedEndpoint endpoint) {
        String label = endpoint.label() == null ? endpoint.providerId() : endpoint.label();
        String model = endpoint.model();

        if (!endpoint.usable()) {
            return new CloudCheckResponse.Item(target, label, endpoint.baseUrl(), model,
                    false, null, 0L, false, "配置不完整，缺少 " + endpoint.missingHint());
        }

        String url = trimTrailingSlash(endpoint.baseUrl()) + "/models";
        long start = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(CHECK_TIMEOUT)
                    .header("Authorization", "Bearer " + endpoint.apiKey())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;
            int status = response.statusCode();

            boolean ok = status == 200;
            String message;
            if (status == 200) {
                message = "连通正常";
            } else if (status == 401 || status == 403) {
                message = "网络可达，但 Key 被拒绝（HTTP " + status + "）。"
                        + "请确认 Key 未过期、且与端点地域匹配 —— 百炼的 Key 与地域绑定，跨地域调用就会返回 401";
            } else if (status == 404) {
                message = "网络可达，但该厂商没有 /models 端点（HTTP 404）。这不代表模型不可用，可用一次真实调用验证";
            } else {
                message = "服务端返回 HTTP " + status + "，请对照厂商错误码表排查";
            }
            // 只记 URL 与状态，绝不含 Key
            log.info("云端自检 {}: {} -> HTTP {}, 耗时 {} ms", target, url, status, cost);
            return new CloudCheckResponse.Item(target, label, url, model, true, status, cost, ok, message);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new CloudCheckResponse.Item(target, label, url, model, false, null,
                    System.currentTimeMillis() - start, false, "自检被中断");
        } catch (Exception ex) {
            long cost = System.currentTimeMillis() - start;
            log.warn("云端自检 {} 失败: {} ({})", target, url, ex.getClass().getSimpleName());
            return new CloudCheckResponse.Item(target, label, url, model, false, null, cost, false,
                    "无法连接（" + ex.getClass().getSimpleName() + "）。请检查网络、代理，或该域名是否被防火墙拦截");
        }
    }

    /**
     * 去掉 base-url 末尾的斜杠，避免拼出 {@code //models}。
     *
     * @param baseUrl 原始地址
     * @return 规整后的地址
     */
    private String trimTrailingSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
