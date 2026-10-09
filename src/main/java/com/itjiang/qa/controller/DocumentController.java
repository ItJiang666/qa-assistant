package com.itjiang.qa.controller;

import com.itjiang.qa.common.ApiResponse;
import com.itjiang.qa.model.dto.DocumentDto;
import com.itjiang.qa.model.dto.UploadResult;
import com.itjiang.qa.service.DocumentService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 文档管理接口（开发文档 4.2.1）。
 *
 * @author Jiang
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * 上传文件并触发入库。
     *
     * <p>支持一次选多个文件（{@code multipart/form-data}，字段名 {@code file}）。
     * 单文件超过 50MB 会被 Spring 在解析阶段拦下，由全局异常处理器翻译成 413。
     *
     * @param file 上传文件
     * @return 受理结果；状态为 PENDING，需轮询详情接口查看进度
     */
    @PostMapping("/upload")
    public ApiResponse<UploadResult> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(documentService.upload(file));
    }

    /**
     * 分页查询文档列表。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return 统一包装的列表
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<DocumentDto> items = documentService.list(page, size);
        return ApiResponse.ok(Map.of(
                "total", documentService.count(),
                "items", items));
    }

    /**
     * 查询单个文档详情（前端轮询入库状态用这个）。
     *
     * @param id 文档 ID
     * @return 文档详情
     */
    @GetMapping("/{id}")
    public ApiResponse<DocumentDto> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(documentService.detail(id));
    }

    /**
     * 删除文档（同时清理向量与落盘文件）。
     *
     * @param id 文档 ID
     * @return 空数据响应
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") Long id) {
        documentService.delete(id);
        return ApiResponse.ok();
    }

    /**
     * 全量重建索引（F10）。
     *
     * <p>换 embedding 模型后<b>必须</b>调用：新旧向量处于不同向量空间，
     * 混在一起不会报错，只会让检索结果悄悄变差。
     *
     * @return 触发重建的文档数
     */
    @PostMapping("/reindex")
    public ApiResponse<Map<String, Object>> reindex() {
        int count = documentService.reindex();
        return ApiResponse.ok(Map.of(
                "reindexed", count,
                "embeddingModel", documentService.currentEmbeddingModel()));
    }
}
