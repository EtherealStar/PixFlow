package com.pixflow.module.conversation.api;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.module.conversation.app.ConfirmationChallengeResponse;
import com.pixflow.module.conversation.app.ConfirmationService;
import com.pixflow.module.conversation.app.ConfirmationSubmitRequest;
import com.pixflow.module.conversation.app.ConfirmationSubmitResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfirmationController {
    private final ConfirmationService confirmationService;

    public ConfirmationController(ConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    @PostMapping("/conversations/{conversationId}/confirm/{proposalId}/challenge")
    public ApiResponse<ConfirmationChallengeResponse> challenge(
            @PathVariable String conversationId,
            @PathVariable String proposalId) {
        return ApiResponse.ok(confirmationService.challenge(conversationId, proposalId));
    }

    @PostMapping("/conversations/{conversationId}/confirm/{proposalId}/submit")
    public ApiResponse<ConfirmationSubmitResponse> submit(
            @PathVariable String conversationId,
            @PathVariable String proposalId,
            @RequestBody(required = false) ConfirmationSubmitRequest request) {
        return ApiResponse.ok(confirmationService.submit(conversationId, proposalId, request));
    }
}
