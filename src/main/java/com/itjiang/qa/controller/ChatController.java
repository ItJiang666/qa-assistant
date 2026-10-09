package com.itjiang.qa.controller;

import com.itjiang.qa.common.ApiResponse;
import com.itjiang.qa.model.dto.ChatRequest;
import com.itjiang.qa.model.dto.ChatResponse;
import com.itjiang.qa.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 问答接口（开发文档 4.2.2）。
 *
 * <p>路径前缀 {@code /api/v1}（决策 17），响应统一包装（决策 18）。
 *
 * @author Jiang
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 一次性返回完整回答。
     *
     * <p>请求头可选带 {@code X-User-Id}；不带时按"无记忆的临时问答"处理，
     * 便于 {@code curl} 直接调试。
     *
     * @param request 问答请求
     * @return 统一包装的问答响应
     */
    @PostMapping
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(chatService.ask(request));
    }
}
