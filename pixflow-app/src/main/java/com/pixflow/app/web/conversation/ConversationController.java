package com.pixflow.app.web.conversation;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.common.web.PageResponse;
import com.pixflow.common.web.Pagination;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.context.CurrentUser;
import com.pixflow.module.conversation.app.ConversationService;
import com.pixflow.module.conversation.app.ConversationView;
import com.pixflow.module.conversation.app.CreateConversationRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
public final class ConversationController {
    private final ConversationService conversations;

    public ConversationController(ConversationService conversations) {
        this.conversations = conversations;
    }

    @PostMapping
    public ApiResponse<ConversationView> create(
            @CurrentUser AuthPrincipal principal,
            @RequestBody(required = false) CreateConversationRequest request) {
        return ApiResponse.ok(conversations.create(principal.userId(), request));
    }

    @GetMapping
    public ApiResponse<PageResponse<ConversationView>> list(
            @CurrentUser AuthPrincipal principal,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size) {
        Pagination pagination = Pagination.of(page, size);
        return ApiResponse.ok(conversations.list(
                principal.userId(), pagination.page(), pagination.size(), false));
    }

    @GetMapping("/{conversationId}")
    public ApiResponse<ConversationView> detail(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId) {
        return ApiResponse.ok(conversations.detail(principal.userId(), conversationId));
    }

    @DeleteMapping("/{conversationId}")
    public ApiResponse<Void> delete(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId) {
        conversations.archive(principal.userId(), conversationId);
        return ApiResponse.ok(null);
    }
}
