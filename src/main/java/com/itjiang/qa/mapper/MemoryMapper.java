package com.itjiang.qa.mapper;

import com.itjiang.qa.model.entity.Memory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 长期记忆持久化。
 *
 * <p><b>所有方法都强制带 {@code userId}</b>，包括按 id 操作的。
 * 理由：这是一条隐私边界。如果删改只按 id，那么拿到别人的记忆 id
 * 就能改别人的数据 —— 本期虽然没有账号体系，匿名 UUID 同样是边界。
 *
 * <h2>为什么不用 INSERT ... ON DUPLICATE KEY UPDATE 做 upsert</h2>
 * <p>那是 MySQL 语法，H2 只在兼容模式下勉强支持，而且
 * {@code VALUES(col)} 这种写法在 MySQL 8 已废弃。
 * 本项目要同时跑 H2 与 MySQL，所以把 upsert 拆成
 * "先 UPDATE，影响 0 行再 INSERT" 两步 —— 全方言通用，代价只是多一次查询，
 * 而记忆抽取是低频异步任务，这点开销完全可接受。
 *
 * @author Jiang
 */
@Mapper
public interface MemoryMapper {

    /**
     * 新增一条记忆。
     *
     * @param memory 记忆实体（id 回填）
     * @return 影响行数
     */
    int insert(Memory memory);

    /**
     * 按用户 + 主题键更新已有记忆。
     *
     * <p>置信度与重要度取较大值：同一主题被反复提到，说明它确实重要，
     * 不应该被后来一条置信度较低的说法削弱。
     *
     * @param memory 新内容（须带 userId 与 memKey）
     * @return 影响行数；0 表示该主题尚不存在
     */
    int updateByUserAndKey(Memory memory);

    /**
     * 查询某用户的一条记忆。
     *
     * @param userId 用户标识
     * @param id     记忆 ID
     * @return 记忆，不存在或不属于该用户时返回 {@code null}
     */
    Memory selectById(@Param("userId") String userId, @Param("id") Long id);

    /**
     * 按用户 + 主题键查询。
     *
     * @param userId 用户标识
     * @param memKey 主题键
     * @return 记忆，不存在时返回 {@code null}
     */
    Memory selectByUserAndKey(@Param("userId") String userId, @Param("memKey") String memKey);

    /**
     * 取出用于注入 Prompt 的记忆，按重要度与新鲜度排序。
     *
     * @param userId 用户标识
     * @param limit  条数上限
     * @return 记忆列表
     */
    List<Memory> selectTopForInject(@Param("userId") String userId, @Param("limit") int limit);

    /**
     * 列出某用户的全部有效记忆（供管理页面使用）。
     *
     * @param userId 用户标识
     * @return 记忆列表
     */
    List<Memory> selectActiveByUser(@Param("userId") String userId);

    /**
     * 统计某用户的有效记忆条数。
     *
     * @param userId 用户标识
     * @return 条数
     */
    long countActiveByUser(@Param("userId") String userId);

    /**
     * 累加注入次数。
     *
     * <p>用于淘汰判断：长期没被注入过的记忆，说明它对当前话题没帮助。
     *
     * @param userId 用户标识
     * @param ids    记忆 ID 列表
     * @return 影响行数
     */
    int increaseHitCount(@Param("userId") String userId, @Param("ids") List<Long> ids);

    /**
     * 修改记忆内容与重要度（用户手工订正）。
     *
     * @param userId     用户标识
     * @param id         记忆 ID
     * @param content    新内容
     * @param importance 新重要度
     * @return 影响行数
     */
    int updateContent(@Param("userId") String userId,
                      @Param("id") Long id,
                      @Param("content") String content,
                      @Param("importance") Integer importance);

    /**
     * 软删一条记忆。
     *
     * <p>用软删而不是物理删除：抽取一定会有抽错的时候，
     * 保留记录才能在问题排查时回答"这条是谁、什么时候、从哪句话里抽出来的"。
     *
     * @param userId 用户标识
     * @param id     记忆 ID
     * @return 影响行数
     */
    int softDelete(@Param("userId") String userId, @Param("id") Long id);

    /**
     * 清空某用户的全部记忆（隐私要求，F12 必须提供）。
     *
     * @param userId 用户标识
     * @return 影响行数
     */
    int deleteAllByUser(@Param("userId") String userId);

    /**
     * 淘汰超出上限的记忆。
     *
     * <p>按 (重要度升序, 更新时间升序) 排序后软删多余的，
     * 也就是"最不重要 + 最久没更新"的先走。
     *
     * <p>写法用"不在保留集里"而不是 LIMIT：H2 的 DELETE 不支持 LIMIT，
     * MySQL 的 DELETE ... LIMIT 也不能带子查询。用 NOT IN 两边都能跑。
     *
     * @param userId 用户标识
     * @param keep   保留条数
     * @return 影响行数
     */
    int evictOverflow(@Param("userId") String userId, @Param("keep") int keep);
}
