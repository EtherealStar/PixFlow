package com.pixflow.app.web.conversation;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.common.web.PageResponse;
import com.pixflow.common.web.Pagination;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.context.CurrentUser;
import com.pixflow.module.conversation.app.ConversationService;
import com.pixflow.module.conversation.app.ConversationView;
import com.pixflow.module.conversation.app.CreateConversationRequest;
import java.time.Instant;
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
    public ApiResponse<ConversationResponse> create(
            @CurrentUser AuthPrincipal principal,
            @RequestBody(required = false) CreateCommand request) {
        CreateConversationRequest ownerRequest = new CreateConversationRequest(request == null ? null : request.title());
        return ApiResponse.ok(ConversationResponse.from(conversations.create(principal.userId(), ownerRequest)));
    }

    @GetMapping
    public ApiResponse<PageResponse<ConversationResponse>> list(
            @CurrentUser AuthPrincipal principal,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size) {
        Pagination pagination = Pagination.of(page, size);
        PageResponse<ConversationView> result = conversations.list(
                principal.userId(), pagination.page(), pagination.size());
        return ApiResponse.ok(PageResponse.of(
                result.records().stream().map(ConversationResponse::from).toList(),
                result.total(), result.page(), result.size()));
    }

    @GetMapping("/{conversationId}")
    public ApiResponse<ConversationResponse> detail(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId) {
        return ApiResponse.ok(ConversationResponse.from(
                conversations.detail(principal.userId(), conversationId)));
    }

    @DeleteMapping("/{conversationId}")
    public ApiResponse<Void> delete(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId) {
        conversations.delete(principal.userId(), conversationId);
        return ApiResponse.ok(null);
    }

    public record CreateCommand(String title) {
    }

    public record ConversationResponse(
            String conversationId,
            String title,
            Instant createdAt,
            Instant updatedAt) {
        static ConversationResponse from(ConversationView view) {
            return new ConversationResponse(
                    view.conversationId(), view.title(), view.createdAt(), view.updatedAt());
        }
    }
}
