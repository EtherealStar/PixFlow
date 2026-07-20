package com.pixflow.app.web.conversation;

import com.pixflow.common.web.ApiResponse;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.context.CurrentUser;
import com.pixflow.app.web.conversation.sse.SseTurnSessionFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations/{conversationId}/turns")
public final class TurnController {
    private final SseTurnSessionFactory sessions;

    public TurnController(SseTurnSessionFactory sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/stop")
    public ApiResponse<Void> stop(
            @CurrentUser AuthPrincipal principal,
            @PathVariable String conversationId) {
        sessions.stop(principal.userId(), conversationId);
        return ApiResponse.ok(null);
    }
}
