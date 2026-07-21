package com.pixflow.app.web.conversation;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.common.web.PageResponse;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.context.CurrentUser;
import com.pixflow.module.conversation.app.HistoryQueryService;
import com.pixflow.module.conversation.history.MessageView;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
public final class HistoryController {
    private final HistoryQueryService history;

    public HistoryController(HistoryQueryService history) {
        this.history = history;
    }

    @GetMapping("/{conversationId}/messages")
    public ApiResponse<PageResponse<MessageResponse>> timeline(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size) {
        PageResponse<MessageView> result = history.timeline(
                principal.userId(), conversationId, page, size);
        return ApiResponse.ok(PageResponse.of(
                result.records().stream().map(MessageResponse::from).toList(),
                result.total(), result.page(), result.size()));
    }

    public record MessageResponse(
            String messageId,
            long seq,
            String role,
            String content,
            List<ReferenceResponse> references,
            Instant createdAt) {
        static MessageResponse from(MessageView view) {
            return new MessageResponse(
                    view.messageId(), view.seq(), view.role(), view.content(),
                    view.references().stream()
                            .map(reference -> new ReferenceResponse(
                                    reference.referenceKey(), reference.displayPathSnapshot()))
                            .toList(),
                    view.createdAt());
        }
    }

    public record ReferenceResponse(String referenceKey, String displayPathSnapshot) {
    }
}
