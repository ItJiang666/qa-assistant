package com.itjiang.qa.controller;

import com.itjiang.qa.common.ApiResponse;
import com.itjiang.qa.context.UserContext;
import com.itjiang.qa.model.dto.MemoryCreateRequest;
import com.itjiang.qa.model.dto.MemoryDto;
import com.itjiang.qa.model.dto.MemoryUpdateRequest;
import com.itjiang.qa.service.LongTermMemoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 长期记忆管理接口（开发文档 4.2.4 / F12）。
 *
 * <p><b>这一组接口不是可选的锦上添花。</b> 模型抽取一定会有抽错的时候，
 * 而"看不见内容、删不掉"的记忆系统，用户第一次发现它记错东西时就会彻底关掉它。
 * 看得见、改得掉、一键清空 —— 这是长期记忆能长期用下去的前提。
 *
 * <p>所有接口都以请求头 {@code X-User-Id} 作为归属依据；
 * 缺失时读接口返回空、写接口报参数错误（无法归属的写入没有意义）。
 *
 * @author Jiang
 */
@RestController
@RequestMapping("/api/v1/memories")
public class MemoryController {

    private final LongTermMemoryService memoryService;

    public MemoryController(LongTermMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * 列出当前用户的长期记忆。
     *
     * @return 记忆列表（按重要度与更新时间排序）
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> list() {
        String userId = UserContext.get();
        List<MemoryDto> items = memoryService.list(userId);
        return ApiResponse.ok(Map.of(
                "total", items.size(),
                "items", items));
    }

    /**
     * 手动新增一条记忆（前端「记住这条」）。
     *
     * <p>{@code memoryType} 允许为空 —— 为空时由服务层按措辞自动判定（见
     * {@link MemoryCreateRequest} 的类注释：一律存成结论会让"记了等于没记"）。
     *
     * @param request 新增请求
     * @return 新建的记忆
     */
    @PostMapping
    public ApiResponse<MemoryDto> create(@Valid @RequestBody MemoryCreateRequest request) {
        return ApiResponse.ok(memoryService.create(
                UserContext.get(), request.content(), request.importance(), request.memoryType()));
    }

    /**
     * 修改记忆内容 / 重要度。
     *
     * @param id      记忆 ID
     * @param request 修改请求
     * @return 修改后的记忆
     */
    @PutMapping("/{id}")
    public ApiResponse<MemoryDto> update(@PathVariable("id") Long id,
                                         @Valid @RequestBody MemoryUpdateRequest request) {
        return ApiResponse.ok(memoryService.update(UserContext.get(), id, request.content(), request.importance()));
    }

    /**
     * 删除一条记忆（软删，保留可追溯）。
     *
     * @param id 记忆 ID
     * @return 空数据响应
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") Long id) {
        memoryService.delete(UserContext.get(), id);
        return ApiResponse.ok();
    }

    /**
     * 清空当前用户的全部长期记忆。
     *
     * <p><b>物理删除</b>，不是软删 —— 用户说"忘掉我"，就得是真忘掉。
     *
     * @return 删除条数
     */
    @DeleteMapping
    public ApiResponse<Map<String, Object>> clear() {
        int deleted = memoryService.clear(UserContext.get());
        return ApiResponse.ok(Map.of("deleted", deleted));
    }
}
