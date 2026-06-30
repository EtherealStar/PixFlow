package com.pixflow.module.conversation.api;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.module.conversation.app.CancellationResult;
import com.pixflow.module.conversation.app.CancellationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CancellationController {
    private final CancellationService cancellationService;

    public CancellationController(CancellationService cancellationService) {
        this.cancellationService = cancellationService;
    }

    @PostMapping("/conversations/{conversationId}/tasks/{taskId}/cancel")
    public ApiResponse<CancellationResult> cancel(@PathVariable String conversationId, @PathVariable String taskId) {
        return ApiResponse.ok(cancellationService.cancel(conversationId, taskId));
    }
}
