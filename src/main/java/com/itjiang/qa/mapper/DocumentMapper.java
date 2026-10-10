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
     * 更新该文档向量所属的模型标识与集合名。
     *
     * <p><b>为什么入库完成后还要再写一次：</b>上传时写入的是"当时"的向量模型；
     * 而全量重建索引（F10）会用"当前"的向量模型重新生成全部向量 ——
     * 此时若不同步这两列，数据库就会与向量库<b>撒谎</b>：
     * 界面显示旧模型名、实际向量却是新模型生成的，后续任何按模型名做的
     * 一致性检查（"要不要重建索引"）都会得出错误结论。
     *
     * @param id              文档 ID
     * @param embeddingModel  实际用于生成向量的模型名
     * @param collectionName  所属向量集合名
     * @return 影响行数
     */
    int updateIndexIdentity(@Param("id") Long id,
                            @Param("embeddingModel") String embeddingModel,
                            @Param("collectionName") String collectionName);

    /**
     * 更新状态为失败并记录原因。
     *
     * @param id           文档 ID
     * @param errorMessage 失败原因
     * @return 影响行数
     */
    int markFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    /**
     * 只写来源说明，<b>不改状态</b>。
     *
     * <p>给 OCR 用：文档正文是识别出来的，需要留痕告诉用户"这不是原件文字层"，
     * 但它的状态必须保持 {@code INDEXED}（它确实已成功入库并能被检索）。
     * 若用 {@link #markFailed} 写留痕，会把状态改成 FAILED，
     * 造成"界面显示失败、而向量其实已在库里能被检索到"的自相矛盾状态。
     *
     * @param id   文档 ID
     * @param note 来源说明
     * @return 影响行数
     */
    int updateOriginNote(@Param("id") Long id, @Param("note") String note);

    /**
     * 删除文档记录。
     *
     * @param id 文档 ID
     * @return 影响行数
     */
    int deleteById(Long id);

    /**
     * 查出所有文档用过的向量模型名（去重）。
     *
     * <p>用于"索引与当前向量模型是否一致"的判断：只要存在任何一个与当前模型不同的值，
     * 就说明库里的向量来自另一个模型 —— 此时要么维度不同直接报错，
     * 要么维度相同但分数被系统性压低（更隐蔽），两种都该提示用户重建索引。
     *
     * @return 去重后的模型名列表
     */
    List<String> selectDistinctEmbeddingModels();

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
