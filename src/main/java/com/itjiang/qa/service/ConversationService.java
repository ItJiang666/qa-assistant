package com.itjiang.qa.service;

import com.itjiang.qa.common.BusinessException;
import com.itjiang.qa.common.ErrorCode;
import com.itjiang.qa.config.QaProperties;
import com.itjiang.qa.mapper.ConversationMapper;
import com.itjiang.qa.mapper.MessageMapper;
import com.itjiang.qa.model.dto.ConversationDto;
import com.itjiang.qa.model.dto.MessageDto;
import com.itjiang.qa.model.entity.Conversation;
import com.itjiang.qa.model.entity.Message;
import com.itjiang.qa.model.enums.RunMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 会话与消息的读写（开发文档 4.2.2）。
 *
 * <h2>归属校验</h2>
 * <p>除"按 ID 查详情"外，所有操作都会校验会话是否属于当前用户。
 * 不校验的后果不是"功能异常"，而是<b>拿到别人的 conversationId 就能读别人的聊天记录</b>。
 * 本期虽然没有账号体系，但匿名 UUID 同样是一条隐私边界，必须守。
 *
 * <h2>匿名用户</h2>
 * <p>请求头 {@code X-User-Id} 缺失时（例如直接 curl 调试），会话归属记为
 * {@link #ANONYMOUS_USER_ID}。这样 L2 会话记忆仍然可用；而 L3 长期记忆会
 * 通过 {@code UserContext} 判定为"未识别用户"从而跳过（见开发文档 4.2 约定）。
 *
 * @author Jiang
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** 未携带 X-User-Id 时的会话归属标识。 */
    public static final String ANONYMOUS_USER_ID = "anonymous";

    /** 首问摘要截取长度：标题只是给人看的索引，不必存整句。 */
    private static final int TITLE_SUMMARY_LENGTH = 50;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final QaProperties properties;

    public ConversationService(ConversationMapper conversationMapper,
                               MessageMapper messageMapper,
                               QaProperties properties) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.properties = properties;
    }

    /**
     * 确保会话存在，返回可用的会话 ID。
     *
     * <p>两种情况：
     * <ul>
     *   <li>调用方没给 ID（首轮对话）→ 服务端生成一个</li>
     *   <li>给了 ID 但库里没有（前端先本地生成再发首问）→ 补建记录，
     *       这样前端可以自己掌控会话 ID，不必等两次往返</li>
     * </ul>
     *
     * @param userId      用户标识，为 {@code null} 时归入匿名用户
     * @param requestedId 调用方传入的会话 ID，可为 {@code null}
     * @param title       首问摘要，用于生成标题
     * @return 可用的会话 ID
     */
    public String ensureConversation(String userId, String requestedId, String title) {
        String owner = normalizeUserId(userId);

        if (requestedId != null && !requestedId.isBlank()) {
            Conversation existing = conversationMapper.selectById(requestedId);
            if (existing != null) {
                // 归属不符时按"不存在"处理：不泄露"这个 ID 存在但不属于你"这一信息
                if (!owner.equals(existing.getUserId())) {
                    log.warn("会话归属校验失败: conversationId={}", requestedId);
                    throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
                }
                return existing.getId();
            }
            createConversation(requestedId, owner, title);
            return requestedId;
        }

        String generated = UUID.randomUUID().toString();
        createConversation(generated, owner, title);
        return generated;
    }

    /**
     * 追加一条消息。
     *
     * <p><b>调用时机很关键</b>：必须在模型调用<b>之后</b>才写用户消息。
     * 因为 {@code JdbcChatMemoryStore.getMessages} 每次都会从库中读取全量历史，
     * 若先写用户消息再调用模型，本轮问题会在上下文里出现两次。
     *
     * @param conversationId 会话 ID
     * @param role           角色：user / assistant
     * @param content        内容
     */
    public void appendMessage(String conversationId, String role, String content) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        messageMapper.insert(message);
        conversationMapper.touch(conversationId);
    }

    /**
     * 查询用户的会话列表。
     *
     * @param userId 用户标识
     * @param page   页码，从 1 开始
     * @param size   每页条数
     * @return 会话列表（最近活跃在前）
     */
    public List<ConversationDto> list(String userId, int page, int size) {
        // 注意：这里不能用 Math.clamp —— 那是 Java 21 才有的 API，
        // 本项目编译目标为 Java 17（--release 17 会在编译期直接报错）
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 1);
        List<Conversation> rows = conversationMapper.selectByUser(
                normalizeUserId(userId), safeSize, (safePage - 1) * safeSize);
        return rows.stream()
                .map(c -> new ConversationDto(c.getId(), c.getTitle(), c.getRunMode(),
                        c.getCreatedAt(), c.getUpdatedAt()))
                .toList();
    }

    /**
     * 查询会话的消息列表。
     *
     * @param conversationId 会话 ID
     * @param userId         用户标识（用于归属校验）
     * @return 消息列表（时间升序）
     */
    public List<MessageDto> messages(String conversationId, String userId) {
        requireOwned(conversationId, userId);
        return messageMapper.selectByConversation(conversationId).stream()
                .map(m -> new MessageDto(m.getId(), m.getRole(), m.getContent(),
                        m.getSourcesJson(), m.getCreatedAt()))
                .toList();
    }

    /**
     * 删除会话及其全部消息。
     *
     * @param conversationId 会话 ID
     * @param userId         用户标识（用于归属校验）
     */
    public void delete(String conversationId, String userId) {
        requireOwned(conversationId, userId);
        // 先删消息：会话记录还在时删除更安全，万一消息删除失败不会留下孤儿数据
        messageMapper.deleteByConversation(conversationId);
        conversationMapper.deleteById(conversationId);
        log.info("已删除会话: conversationId={}", conversationId);
    }

    /**
     * 校验会话存在且属于该用户。
     *
     * @param conversationId 会话 ID
     * @param userId         用户标识
     */
    private void requireOwned(String conversationId, String userId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null
                || !normalizeUserId(userId).equals(conversation.getUserId())) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
    }

    /**
     * 落库一个新会话。
     *
     * @param id    会话 ID
     * @param owner 归属用户
     * @param title 标题（首问摘要）
     */
    private void createConversation(String id, String owner, String title) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setUserId(owner);
        conversation.setTitle(summarize(title));
        conversation.setRunMode(RunMode.from(properties.getMode()).getValue());
        conversationMapper.insert(conversation);
        log.debug("新建会话: id={}, userId={}", id, owner);
    }

    /**
     * 把首问压成标题。
     *
     * @param title 原始首问
     * @return 截断后的标题
     */
    private String summarize(String title) {
        if (title == null || title.isBlank()) {
            return "新对话";
        }
        String trimmed = title.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= TITLE_SUMMARY_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, TITLE_SUMMARY_LENGTH) + "…";
    }

    /**
     * 归一化用户标识。
     *
     * @param userId 原始用户标识，可能为 {@code null}
     * @return 非空的归属标识
     */
    private String normalizeUserId(String userId) {
        return (userId == null || userId.isBlank()) ? ANONYMOUS_USER_ID : userId;
    }
}
