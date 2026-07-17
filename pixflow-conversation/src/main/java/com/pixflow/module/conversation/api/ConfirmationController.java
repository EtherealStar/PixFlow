package com.pixflow.module.conversation.api;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.context.CurrentUser;
import com.pixflow.module.conversation.app.ConfirmationService;
import com.pixflow.module.conversation.app.ConfirmationSubmitResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public final class ConfirmationController {
    private final ConfirmationService confirmationService;

    public ConfirmationController(ConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @PostMapping("/conversations/{conversationId}/proposals/{proposalId}/confirm")
    public ApiResponse<ConfirmationSubmitResponse> confirm(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId,
            @PathVariable String proposalId) {
        return ApiResponse.ok(confirmationService.confirm(principal, conversationId, proposalId));
    }

    @PostMapping("/conversations/{conversationId}/proposals/{proposalId}/reject")
    public ApiResponse<Void> reject(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId,
            @PathVariable String proposalId) {
        confirmationService.reject(principal, conversationId, proposalId);
        return ApiResponse.ok(null);
    }
}
