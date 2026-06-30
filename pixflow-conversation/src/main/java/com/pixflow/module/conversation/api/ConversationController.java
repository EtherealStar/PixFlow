package com.pixflow.module.conversation.api;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.common.web.PageResponse;
import com.pixflow.common.web.Pagination;
import com.pixflow.module.conversation.app.ConversationService;
import com.pixflow.module.conversation.app.ConversationView;
import com.pixflow.module.conversation.app.CreateConversationRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConversationController {
    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping("/conversations")
    public ApiResponse<ConversationView> create(@RequestBody(required = false) CreateConversationRequest request) {
        return ApiResponse.ok(conversationService.create(request));
    }

    @GetMapping("/conversations")
    public ApiResponse<PageResponse<ConversationView>> list(
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        Pagination pagination = Pagination.of(page, size);
        return ApiResponse.ok(conversationService.list(pagination.page(), pagination.size(), includeArchived));
    }

    @GetMapping("/conversations/{conversationId}")
    public ApiResponse<ConversationView> detail(@PathVariable String conversationId) {
        return ApiResponse.ok(conversationService.detail(conversationId));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ApiResponse<Void> archive(@PathVariable String conversationId) {
        conversationService.archive(conversationId);
        return ApiResponse.ok(null);
    }
}
