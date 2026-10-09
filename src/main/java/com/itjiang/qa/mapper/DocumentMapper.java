package com.itjiang.qa.mapper;

import com.itjiang.qa.model.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档元数据持久化。
 *
 * @author Jiang
 */
@Mapper
public interface DocumentMapper {

    /**
     * 新增文档记录（上传后立即调用，状态为 PENDING）。
     *
     * @param document 文档实体（id 回填）
     * @return 影响行数
     */
    int insert(Document document);

    /**
     * 按主键查询。
     *
     * @param id 文档 ID
     * @return 文档，不存在时返回 {@code null}
     */
    Document selectById(Long id);

    /**
     * 分页查询文档列表。
     *
     * @param limit  每页条数
     * @param offset 偏移量
     * @return 文档列表（最新在前）
     */
    List<Document> selectPage(@Param("limit") int limit, @Param("offset") int offset);

    /**
     * 统计文档总数。
     *
     * @return 总数
     */
    long countAll();

    /**
     * 统计所有文档的分块总数。
     *
     * <p>用于系统状态接口。用 SUM 而不是查 count(*)，是因为"分块数"才是
     * 反映向量库真实规模的那个指标。
     *
     * @return 分块总数
     */
    long sumChunkCount();

    /**
     * 更新状态为处理中。
     *
     * @param id 文档 ID
     * @return 影响行数
     */
    int markParsing(@Param("id") Long id);

    /**
     * 更新状态为已入库。
     *
     * @param id         文档 ID
     * @param chunkCount 分块数
     * @return 影响行数
     */
    int markIndexed(@Param("id") Long id, @Param("chunkCount") int chunkCount);

    /**
     * 更新状态为失败并记录原因。
     *
     * @param id           文档 ID
     * @param errorMessage 失败原因
     * @return 影响行数
     */
    int markFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    /**
     * 删除文档记录。
     *
     * @param id 文档 ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 查出全部文档，用于全量重建索引。
     *
     * @return 全部文档
     */
    List<Document> selectAll();

    /**
     * 查出所有不是"已入库"状态的文档，用于崩溃后恢复。
     *
     * <p>服务重启时，停留在 PARSING 状态的记录说明上次处理到一半被中断了，
     * 它们既检索不到、又不会自己重试 —— 必须能被找出来重新处理或标记失败。
     *
     * @return 未完成文档列表
     */
    List<Document> selectUnfinished();
}
