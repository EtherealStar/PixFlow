package com.pixflow.app.web.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.pixflow.common.web.ApiResponse;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.context.CurrentUser;
import com.pixflow.module.conversation.app.ConfirmationService;
import com.pixflow.module.conversation.app.ConfirmationSubmitResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/conversations/{conversationId}/proposals")
public final class ProposalController {
    private final ConfirmationService confirmations;

    public ProposalController(ConfirmationService confirmations) {
        this.confirmations = confirmations;
    }

    @PostMapping("/{proposalId}/confirm")
    public ApiResponse<ConfirmationResponse> confirm(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId,
            @PathVariable String proposalId,
            @RequestBody(required = false) JsonNode body) {
        requireEmptyBody(body);
        return ApiResponse.ok(ConfirmationResponse.from(
                confirmations.confirm(principal, conversationId, proposalId)));
    }

    @PostMapping("/{proposalId}/reject")
    public ApiResponse<Void> reject(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId,
            @PathVariable String proposalId,
            @RequestBody(required = false) JsonNode body) {
        requireEmptyBody(body);
        confirmations.reject(principal, conversationId, proposalId);
        return ApiResponse.ok(null);
    }

    /** Proposal ID 已经是业务幂等身份，命令不接受额外客户端状态。 */
    private static void requireEmptyBody(JsonNode body) {
        if (body != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Proposal 命令请求体必须为空");
        }
    }

    public record ConfirmationResponse(String proposalId, String taskId, String status) {
        static ConfirmationResponse from(ConfirmationSubmitResponse response) {
            return new ConfirmationResponse(
                    response.proposalId(), response.taskId(), response.status());
        }
    }
}
