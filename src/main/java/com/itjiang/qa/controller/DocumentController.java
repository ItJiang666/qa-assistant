package com.itjiang.qa.controller;

import com.itjiang.qa.common.ApiResponse;
import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.model.dto.DocumentDto;
import com.itjiang.qa.model.dto.SearchRequest;
import com.itjiang.qa.model.dto.SearchResponse;
import com.itjiang.qa.model.dto.UploadResult;
import com.itjiang.qa.rag.QueryExpander;
import com.itjiang.qa.rag.RoundRobinMerge;
import com.itjiang.qa.service.DocumentService;
import com.itjiang.qa.service.VectorStoreService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
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
    private final VectorStoreService vectorStoreService;
    private final QaProperties properties;
    private final QueryExpander queryExpander;

    public DocumentController(DocumentService documentService,
                              VectorStoreService vectorStoreService,
                              QaProperties properties,
                              QueryExpander queryExpander) {
        this.documentService = documentService;
        this.vectorStoreService = vectorStoreService;
        this.properties = properties;
        this.queryExpander = queryExpander;
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

    /**
     * 检索试跑：只做向量检索，不做生成。
     *
     * <p><b>为什么单独开一个接口：</b>调检索参数时若每次都走完整问答链路，
     * 就把"模型这一轮答得好不好"混了进来，而且每次都要花一次生成的时间与费用。
     * 把检索单独拎出来之后，评测集可以反复跑（十几个探针一秒出结果、几乎零成本），
     * 从而把"召回率"这件事变成可测量的数字，而不是靠感觉调参。
     *
     * <p>它返回的是向量库的<b>原始命中</b>：不注入空资料占位符、不做任何防幻觉处理 ——
     * 评测要看到的正是检索的真实水平。
     *
     * @param request 查询与参数
     * @return 命中明细
     */
    @PostMapping("/search")
    public ApiResponse<SearchResponse> search(@RequestBody @Valid SearchRequest request) {
        // 未传参数时用**配置里的默认值**，而不是本接口自己的一套默认值 ——
        // 否则试跑结果与实际问答用的参数不一致，调参会调错方向
        int topK = request.topKOrDefault(properties.getRetrieval().getTopK());
        double minScore = request.minScoreOrDefault(properties.getRetrieval().getMinScore());

        List<VectorStoreService.SearchHit> hits = search(request.query(), topK, minScore, request.expandOrDefault());
        List<SearchResponse.Hit> items = hits.stream()
                .map(hit -> new SearchResponse.Hit(
                        hit.documentId(), hit.fileName(),
                        hit.score() == null ? 0.0 : hit.score(), hit.text()))
                .toList();
        return ApiResponse.ok(new SearchResponse(request.query(), topK, minScore, items.size(), items));
    }

    /**
     * 检索（可选多路改写）。
     *
     * <p>多路改写时走的是**与线上问答同一套**逻辑：{@link QueryExpander} 生成改写式，
     * {@link RoundRobinMerge} 轮转合并去重 —— 共用同一个合并实现，
     * 避免"评测看到的规则和线上不是一套"这种最坑人的偏差。
     *
     * @param query    查询文本
     * @param topK     条数上限
     * @param minScore 相似度阈值
     * @param expand   是否启用查询改写
     * @return 命中的片段
     */
    private List<VectorStoreService.SearchHit> search(String query, int topK, double minScore, boolean expand) {
        if (!expand || !properties.getRetrieval().getQueryExpansion().isEnabled()) {
            return vectorStoreService.search(query, topK, minScore);
        }
        List<String> queries = queryExpander.expand(query);
        List<List<VectorStoreService.SearchHit>> ranked = new ArrayList<>(queries.size());
        for (String one : queries) {
            ranked.add(vectorStoreService.search(one, topK, minScore));
        }
        return RoundRobinMerge.merge(ranked, topK, VectorStoreService.SearchHit::text);
    }
}
