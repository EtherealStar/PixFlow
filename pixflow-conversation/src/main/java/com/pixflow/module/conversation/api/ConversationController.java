package com.pixflow.module.conversation.api;

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
@RequestMapping("/api")
public class ConversationController {
    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping("/conversations")
    public ApiResponse<ConversationView> create(
            @CurrentUser AuthPrincipal principal,
            @RequestBody(required = false) CreateConversationRequest request) {
        return ApiResponse.ok(conversationService.create(principal.userId(), request));
    }

    @GetMapping("/conversations")
    public ApiResponse<PageResponse<ConversationView>> list(
            @CurrentUser AuthPrincipal principal,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        Pagination pagination = Pagination.of(page, size);
        return ApiResponse.ok(conversationService.list(
                principal.userId(), pagination.page(), pagination.size(), includeArchived));
    }

    @GetMapping("/conversations/{conversationId}")
    public ApiResponse<ConversationView> detail(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId) {
        return ApiResponse.ok(conversationService.detail(principal.userId(), conversationId));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ApiResponse<Void> archive(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId) {
        conversationService.archive(principal.userId(), conversationId);
        return ApiResponse.ok(null);
    }
}
