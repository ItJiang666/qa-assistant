package com.itjiang.qa.controller;

import com.itjiang.qa.common.ApiResponse;
import com.itjiang.qa.model.dto.ChatModeRequest;
import com.itjiang.qa.model.dto.CloudCheckResponse;
import com.itjiang.qa.model.dto.SystemStatusResponse;
import com.itjiang.qa.provider.CloudEndpointResolver;
import com.itjiang.qa.service.CloudConnectivityService;
import com.itjiang.qa.service.RuntimeModeService;
import com.itjiang.qa.service.SystemStatusService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统状态与模型来源接口（F9）。
 *
 * <p>控制器只做三件事：接请求、调服务、包响应。任何判断逻辑都不应写在这一层 ——
 * 否则一旦要在别处复用同样的逻辑，就只能复制粘贴。
 *
 * @author Jiang
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final SystemStatusService systemStatusService;
    private final RuntimeModeService runtimeModeService;
    private final CloudConnectivityService connectivityService;
    private final CloudEndpointResolver cloudEndpointResolver;

    public SystemController(SystemStatusService systemStatusService,
                            RuntimeModeService runtimeModeService,
                            CloudConnectivityService connectivityService,
                            CloudEndpointResolver cloudEndpointResolver) {
        this.systemStatusService = systemStatusService;
        this.runtimeModeService = runtimeModeService;
        this.connectivityService = connectivityService;
        this.cloudEndpointResolver = cloudEndpointResolver;
    }

    /**
     * 查询当前系统状态。
     *
     * <p>不需要携带 {@code X-User-Id}：未携带时 {@code memoryCount} 为 0、
     * {@code userIdentified} 为 false，接口照常工作。
     *
     * @return 统一包装的系统状态
     */
    @GetMapping("/status")
    public ApiResponse<SystemStatusResponse> status() {
        return ApiResponse.ok(systemStatusService.currentStatus());
    }

    /**
     * 切换对话模式（本地 ⇄ 云端），立即生效、无需重启。
     *
     * <p>只能切<b>对话</b>模型：向量模型的来源在启动时定死（换向量模型等于换向量空间，
     * 必须连索引一起重建）。切到云端前会校验端点三要素齐备，缺 Key 就当场拒绝 ——
     * 让用户"切换成功"之后 401，比直接拒绝更糟。
     *
     * <p>切换结果返回<b>整份最新状态</b>，前端不必再补一次 /status 请求。
     *
     * @param request 目标模式
     * @return 切换后的系统状态
     */
    @PostMapping("/chat-mode")
    public ApiResponse<SystemStatusResponse> switchChatMode(@RequestBody @Valid ChatModeRequest request) {
        runtimeModeService.switchTo(RuntimeModeService.Mode.from(request.mode()));
        return ApiResponse.ok(systemStatusService.currentStatus());
    }

    /**
     * 云端连通性自检。
     *
     * <p>用 POST 而不是 GET：它会产生对外部服务的真实请求（有副作用、有耗时），
     * 不该被浏览器或网关当成可缓存的读操作。
     *
     * @return 各端点的检查结果
     */
    @PostMapping("/cloud-check")
    public ApiResponse<CloudCheckResponse> cloudCheck() {
        return ApiResponse.ok(connectivityService.checkAll());
    }

    /**
     * 内置厂商预设清单（只读，供界面展示）。
     *
     * <p><b>为什么值得单独开一个接口：</b>国内厂商的差异不止 base-url 与模型名，
     * 还有"哪家能当向量源"这种一配错就必然失败的坑（方舟的 embedding 不走 OpenAI 协议，
     * Kimi / MiniMax / 阶跃根本没有 embedding 接口）。把这些连同"Key 配没配"一起摆在界面上，
     * 用户就不必翻文档，也不会配出一个注定 404 的组合。
     *
     * <p>⚠️ 每个预置只回 Key 的**布尔状态**，绝不回显内容。
     *
     * @return 预设清单
     */
    @GetMapping("/cloud-presets")
    public ApiResponse<List<CloudEndpointResolver.PresetView>> cloudPresets() {
        return ApiResponse.ok(cloudEndpointResolver.presetViews());
    }
}
