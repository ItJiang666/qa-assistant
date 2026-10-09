package com.itjiang.qa.mapper;

import com.itjiang.qa.model.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会话持久化。
 *
 * <p>所有查询方法都<b>强制带 userId</b>（除按主键查详情外）：
 * 会话属于某个人，漏掉这个条件就意味着"别人的会话可能出现在你的列表里"。
 * 把参数做成必填而不是可选，是为了让编译器帮忙记住这件事。
 *
 * @author Jiang
 */
@Mapper
public interface ConversationMapper {

    /**
     * 新增会话。
     *
     * @param conversation 会话实体
     * @return 影响行数
     */
    int insert(Conversation conversation);

    /**
     * 按主键查询。
     *
     * @param id 会话 ID
     * @return 会话，不存在时返回 {@code null}
     */
    Conversation selectById(String id);

    /**
     * 查询某用户的会话列表，按最近更新倒序。
     *
     * @param userId 用户标识
     * @param limit  每页条数
     * @param offset 偏移量
     * @return 会话列表
     */
    List<Conversation> selectByUser(@Param("userId") String userId,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    /**
     * 统计某用户的会话总数（用于分页）。
     *
     * @param userId 用户标识
     * @return 总数
     */
    long countByUser(@Param("userId") String userId);

    /**
     * 更新会话标题。
     *
     * @param id    会话 ID
     * @param title 新标题
     * @return 影响行数
     */
    int updateTitle(@Param("id") String id, @Param("title") String title);

    /**
     * 刷新会话的最后活跃时间。
     *
     * <p><b>显式写 updated_at，而不是依赖数据库的 ON UPDATE CURRENT_TIMESTAMP</b> ——
     * H2 不支持那个语法，靠数据库自动更新会导致 H2 与 MySQL 行为不一致（开发文档 4.1）。
     *
     * @param id 会话 ID
     * @return 影响行数
     */
    int touch(@Param("id") String id);

    /**
     * 删除会话。
     *
     * @param id 会话 ID
     * @return 影响行数
     */
    int deleteById(String id);
}
