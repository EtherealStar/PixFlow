package com.pixflow.module.conversation.api;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.context.CurrentUser;
import com.pixflow.module.conversation.app.CancellationResult;
import com.pixflow.module.conversation.app.CancellationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CancellationController {
    private final CancellationService cancellationService;

    public CancellationController(CancellationService cancellationService) {
        this.cancellationService = cancellationService;
    }

    @PostMapping("/conversations/{conversationId}/tasks/{taskId}/cancel")
    public ApiResponse<CancellationResult> cancel(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId,
            @PathVariable String taskId) {
        return ApiResponse.ok(cancellationService.cancel(principal.userId(), conversationId, taskId));
    }
}
