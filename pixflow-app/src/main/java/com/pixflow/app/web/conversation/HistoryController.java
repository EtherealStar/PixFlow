package com.pixflow.app.web.conversation;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.common.web.PageResponse;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.context.CurrentUser;
import com.pixflow.module.conversation.app.HistoryQueryService;
import com.pixflow.module.conversation.history.MessageView;
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
    public ApiResponse<PageResponse<MessageView>> timeline(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size) {
        return ApiResponse.ok(history.timeline(principal.userId(), conversationId, page, size));
    }
}
