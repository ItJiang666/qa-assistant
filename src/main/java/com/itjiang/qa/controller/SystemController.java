package com.itjiang.qa.controller;

import com.itjiang.qa.common.ApiResponse;
import com.itjiang.qa.model.dto.SystemStatusResponse;
import com.itjiang.qa.service.SystemStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统状态接口（F9）。
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

    public SystemController(SystemStatusService systemStatusService) {
        this.systemStatusService = systemStatusService;
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
}
