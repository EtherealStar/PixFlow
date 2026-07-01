package com.pixflow.app.auth;

import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.error.AuthErrorCode;
import com.pixflow.infra.auth.error.AuthException;
import com.pixflow.infra.auth.service.AuthService;
import java.security.Principal;
import java.util.List;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.util.StringUtils;

public class AuthWebSocketInterceptor implements ChannelInterceptor {
    private final AuthService authService;

    public AuthWebSocketInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            AuthPrincipal principal = authService.authenticateAccessToken(extractToken(accessor));
            accessor.setUser(new StompAuthPrincipal(principal));
        }
        return message;
    }

    private static String extractToken(StompHeaderAccessor accessor) {
        String authorization = first(accessor.getNativeHeader("Authorization"));
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }
        String fallback = first(accessor.getNativeHeader("X-Auth-Token"));
        if (StringUtils.hasText(fallback)) {
            return fallback.trim();
        }
        throw new AuthException(AuthErrorCode.AUTH_TOKEN_MISSING, "STOMP CONNECT 缺少 access token");
    }

    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private record StompAuthPrincipal(AuthPrincipal authPrincipal) implements Principal {
        @Override
        public String getName() {
            return authPrincipal.username();
        }
    }
}
