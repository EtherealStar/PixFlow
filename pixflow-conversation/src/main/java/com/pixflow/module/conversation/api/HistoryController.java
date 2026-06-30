package com.pixflow.module.conversation.api;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.common.web.PageResponse;
import com.pixflow.module.conversation.app.HistoryQueryService;
import com.pixflow.module.conversation.history.MessageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HistoryController {
    private final HistoryQueryService historyQueryService;

    public HistoryController(HistoryQueryService historyQueryService) {
        this.historyQueryService = historyQueryService;
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<PageResponse<MessageView>> timeline(
            @PathVariable String conversationId,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size) {
        return ApiResponse.ok(historyQueryService.timeline(conversationId, page, size));
    }
}
