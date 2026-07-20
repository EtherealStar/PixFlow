package com.pixflow.app.web.conversation;

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
@RequestMapping("/api/conversations/{conversationId}/proposals")
public final class ProposalController {
    private final ConfirmationService confirmations;

    public ProposalController(ConfirmationService confirmations) {
        this.confirmations = confirmations;
    }

    @PostMapping("/{proposalId}/confirm")
    public ApiResponse<ConfirmationSubmitResponse> confirm(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId,
            @PathVariable String proposalId) {
        return ApiResponse.ok(confirmations.confirm(principal, conversationId, proposalId));
    }

    @PostMapping("/{proposalId}/reject")
    public ApiResponse<Void> reject(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId,
            @PathVariable String proposalId) {
        confirmations.reject(principal, conversationId, proposalId);
        return ApiResponse.ok(null);
    }
}
