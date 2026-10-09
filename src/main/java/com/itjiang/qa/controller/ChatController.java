package com.itjiang.qa.controller;

import com.itjiang.qa.common.ApiResponse;
import com.itjiang.qa.context.UserContext;
import com.itjiang.qa.model.dto.ChatRequest;
import com.itjiang.qa.model.dto.ChatResponse;
import com.itjiang.qa.model.dto.ConversationDto;
import com.itjiang.qa.model.dto.MessageDto;
import com.itjiang.qa.service.ChatService;
import com.itjiang.qa.service.ConversationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 问答与会话接口（开发文档 4.2.2）。
 *
 * <p>路径前缀 {@code /api/v1}（决策 17），响应统一包装（决策 18）——
 * <b>唯一的例外是 {@code /stream}</b>：SSE 是事件流，套不了 JSON 外壳。
 *
 * @author Jiang
 */
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private final ChatService chatService;
    private final ConversationService conversationService;

    public ChatController(ChatService chatService, ConversationService conversationService) {
        this.chatService = chatService;
        this.conversationService = conversationService;
    }

    /**
     * 一次性返回完整回答。
     *
     * @param request 问答请求
     * @return 统一包装的问答响应
     */
    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(chatService.ask(request));
    }

    /**
     * 流式返回回答（SSE）。
     *
     * <p>事件协议：
     * <pre>
     *   event: meta    {"conversationId":"..."}        —— 立刻告知会话 ID
     *   event: delta   {"text":"增量的几个字"}          —— 逐段推送，前端追加渲染
     *   event: done    {"conversationId","elapsedMs","model"}
     *   event: error   {"message":"人能看懂的提示"}
     * </pre>
     *
     * <p>返回类型不是 {@code ApiResponse} —— 这是有意的例外，见类注释。
     *
     * @param request 问答请求
     * @return SSE 发射器
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        return chatService.stream(request);
    }

    /**
     * 当前用户的历史会话列表。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数
     * @return 会话列表
     */
    @GetMapping("/conversations")
    public ApiResponse<List<ConversationDto>> conversations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(conversationService.list(UserContext.get(), page, size));
    }

    /**
     * 拉取某个会话的历史消息。
     *
     * <p>前端重开会话时也用它恢复 L2 记忆的展示（记忆本身由服务端从库里读，
     * 不依赖前端回传历史）。
     *
     * @param id 会话 ID
     * @return 消息列表
     */
    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<MessageDto>> messages(@PathVariable("id") String id) {
        return ApiResponse.ok(conversationService.messages(id, UserContext.get()));
    }

    /**
     * 删除会话（连同消息与记忆）。
     *
     * @param id 会话 ID
     * @return 空数据响应
     */
    @DeleteMapping("/conversations/{id}")
    public ApiResponse<Void> deleteConversation(@PathVariable("id") String id) {
        conversationService.delete(id, UserContext.get());
        return ApiResponse.ok();
    }
}
