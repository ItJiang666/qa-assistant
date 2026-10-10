package com.itjiang.qa.memory;

import com.itjiang.qa.mapper.MessageMapper;
import com.itjiang.qa.model.entity.Message;
import com.itjiang.qa.rag.Prompts;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆的 MySQL/H2 实现 —— 让 L2 记忆<b>重启不丢</b>（开发文档 4.6.7）。
 *
 * <h2>核心设计：读多写零</h2>
 *
 * <p>这个类的三个方法里，只有 {@link #getMessages(Object)} 干活，
 * {@link #updateMessages(Object, List)} <b>是空的，而且这是故意的</b>。
 * 理由如下，改代码前请先读完：
 *
 * <ol>
 *   <li><b>数据只有一个写入方。</b> 对话历史由 {@code ChatService} 落库
 *       （它同时要写 sources、token 用量等 ChatMemory 根本不认识的字段）。
 *       如果这里也写一遍，同一条消息会入库两次。</li>
 *   <li><b>框架给的列表是"裁剪后的窗口"，不是"全部历史"。</b>
 *       {@code updateMessages} 收到的是 TokenWindow 裁过的结果。
 *       照它"先删后插"，会把窗口外的历史<b>永久删掉</b> ——
 *       表现为"聊得越久，早期的对话消失得越快"，且完全无声。</li>
 *   <li><b>不需要写也能工作。</b> {@code getMessages} 每次都从库里读<b>全量</b>历史，
 *       再由 ChatMemory 按 token 窗口裁剪成"给模型看的那一段"。
 *       于是"存多少"与"给模型看多少"彻底解耦 —— 存全量，看窗口。</li>
 * </ol>
 *
 * <p>代价：每轮对话都会把整个会话的消息读出来。单会话几百条消息的量级下可以忽略；
 * 若将来单会话消息数到达万级，应在 {@code selectByConversation} 上加
 * "只取最近 N 条"的限制（注意那时要按 id 倒序取再反转，不能用 ASC + LIMIT）。
 *
 * @author Jiang
 */
@Component
public class JdbcChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcChatMemoryStore.class);

    private final MessageMapper messageMapper;
    private final TokenCountEstimator estimator;

    public JdbcChatMemoryStore(MessageMapper messageMapper, TokenCountEstimator estimator) {
        this.messageMapper = messageMapper;
        this.estimator = estimator;
    }

    /**
     * 读取指定会话的全部历史消息。
     *
     * <p><b>必须返回可变列表。</b> 框架拿到之后会往里面追加本轮的新消息，
     * 返回 {@code List.of(...)} 或 {@code Stream.toList()} 会在第二轮直接抛
     * {@code UnsupportedOperationException} —— 这个坑只在"第二次提问"时才出现，
     * 首次联调很容易漏掉。
     *
     * @param memoryId 会话 ID（由 {@code @MemoryId} 传入）
     * @return 按时间升序的消息列表，永不为 {@code null}
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String conversationId = String.valueOf(memoryId);
        List<Message> rows = messageMapper.selectByConversation(conversationId);

        List<ChatMessage> messages = new ArrayList<>(rows.size() + 1);

        // ★ 系统提示词必须在这里拼上，而不是靠 @AiService 上的 @SystemMessage。
        //
        //   原因（实测踩过，不是推测）：一旦配置了 chatMemoryStore，LangChain4j 的
        //   ChatMemory 就以 store 为准 —— 框架把 system 消息 add 进内存后会调用
        //   updateMessages()，而我们为了让对话历史只由 ChatService 唯一落库，
        //   那个方法是刻意留空的；紧接着框架又从 getMessages() 重新读取，
        //   于是 framework 加进去的 system 消息当场丢失。
        //
        //   实测现象：请求体里 messages 数组只有一条 user 消息，system 段完全不存在，
        //   铁律与长期记忆全部没进 Prompt。表现是"提示词写了但模型不听话"，
        //   而且不会有任何报错 —— 属于最难发现的一类问题。
        messages.add(SystemMessage.from(Prompts.SYSTEM));

        for (Message row : rows) {
            ChatMessage message = toChatMessage(row);
            if (message != null) {
                messages.add(message);
            }
        }
        log.debug("装载会话记忆: conversationId={}, 历史消息 {} 条（另含系统提示词）",
                conversationId, messages.size() - 1);
        // 顺带给出 token 估算：只有条数无法判断"是不是已经撑满窗口将要被裁"——
        // 一轮长文档问答就能吃掉 2000 token，20 条也可能已经超预算。
        // 与 ChatMemory 的裁剪用同一个估算器，两处口径一致才有可比性。
        if (log.isDebugEnabled()) {
            log.debug("会话记忆 token 估算: conversationId={}, 全量 {} token（窗口会在此基础上裁剪）",
                    conversationId, estimator.estimateTokenCountInMessages(messages));
        }
        return messages;
    }

    /**
     * <b>刻意留空。</b>
     *
     * <p>写入由 {@code ChatService} 负责；这里若再写一遍会造成消息重复，
     * 而按"窗口内容"覆盖写则会把历史永久截断。完整论证见类注释。
     *
     * @param memoryId 会话 ID
     * @param messages 框架内存中的窗口消息（此处有意忽略）
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // 有意不实现：见类注释「核心设计：读多写零」
    }

    /**
     * 删除会话的全部记忆。
     *
     * <p>删除会话时调用。这里删的是 {@code qa_message} —— 它同时是对话历史，
     * 所以"清空记忆"与"清空聊天记录"在本项目里是同一件事，符合用户预期。
     *
     * @param memoryId 会话 ID
     */
    @Override
    public void deleteMessages(Object memoryId) {
        String conversationId = String.valueOf(memoryId);
        int deleted = messageMapper.deleteByConversation(conversationId);
        log.info("已清除会话记忆: conversationId={}, 删除 {} 条", conversationId, deleted);
    }

    /**
     * 数据库行 → LangChain4j 消息。
     *
     * <p>遇到不认识的角色返回 {@code null} 并记日志，而不是抛异常：
     * 一条脏数据不应该让整个会话打不开。
     *
     * @param row 数据库行
     * @return ChatMessage，无法识别时返回 {@code null}
     */
    private ChatMessage toChatMessage(Message row) {
        String content = row.getContent() == null ? "" : row.getContent();
        String role = row.getRole() == null ? "" : row.getRole().toLowerCase();
        return switch (role) {
            case "user" -> UserMessage.from(content);
            case "assistant", "ai" -> AiMessage.from(content);
            case "system" -> SystemMessage.from(content);
            default -> {
                log.warn("忽略无法识别的消息角色: role={}, conversationId={}", row.getRole(), row.getConversationId());
                yield null;
            }
        };
    }
}
