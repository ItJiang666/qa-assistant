package com.itjiang.qa.mapper;

import com.itjiang.qa.model.entity.Message;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 消息持久化。
 *
 * <p>这里的读写同时服务于两个调用方：
 * <ul>
 *   <li>{@code ChatService} —— 落库完整对话历史</li>
 *   <li>{@code JdbcChatMemoryStore} —— 读出历史交给 ChatMemory 裁剪成窗口</li>
 * </ul>
 * 注意两者对"顺序"的要求不同：ChatService 插入的顺序无所谓（有 id 排序兜底），
 * 而记忆读取<b>必须按 id 升序</b>，否则模型看到的是倒着的对话。
 *
 * @author Jiang
 */
@Mapper
public interface MessageMapper {

    /**
     * 新增一条消息。
     *
     * @param message 消息实体（{@code id} 由数据库生成，回填到实体）
     * @return 影响行数
     */
    int insert(Message message);

    /**
     * 按会话读取全部消息，<b>按 id 升序</b>。
     *
     * <p>不用分页：单会话的消息量级很小（几百条），而记忆窗口需要看到最新的一段。
     * 真到需要分页时，前端接口另开一个带 limit 的方法，不要动这个语义。
     *
     * @param conversationId 会话 ID
     * @return 消息列表（升序）
     */
    List<Message> selectByConversation(String conversationId);

    /**
     * 统计会话内的消息条数。
     *
     * @param conversationId 会话 ID
     * @return 条数
     */
    long countByConversation(String conversationId);

    /**
     * 删除会话下的全部消息。
     *
     * @param conversationId 会话 ID
     * @return 影响行数
     */
    int deleteByConversation(String conversationId);
}
