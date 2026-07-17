package com.pixflow.app.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.error.AuthErrorCode;
import com.pixflow.infra.auth.error.AuthException;
import com.pixflow.infra.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class AuthWebSocketInterceptorTest {
    private final AuthService authService = mock(AuthService.class);
    private final AuthWebSocketInterceptor interceptor = new AuthWebSocketInterceptor(authService);
    private final MessageChannel channel = mock(MessageChannel.class);

    @Test
    void connectUsesTheSameAccessTokenEligibilityBoundary() {
        when(authService.authenticateAccessToken("access-token"))
                .thenReturn(new AuthPrincipal(1L, "pixflow", "PixFlow Administrator"));

        Message<?> result = interceptor.preSend(connect("access-token"), channel);

        assertThat(StompHeaderAccessor.wrap(result).getUser()).isNotNull();
        assertThat(StompHeaderAccessor.wrap(result).getUser().getName()).isEqualTo("pixflow");
    }

    @Test
    void connectRejectsAFormerAdministratorToken() {
        when(authService.authenticateAccessToken("old-token"))
                .thenThrow(new AuthException(AuthErrorCode.AUTH_TOKEN_INVALID, "access token 校验失败"));

        assertThatThrownBy(() -> interceptor.preSend(connect("old-token"), channel))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_TOKEN_INVALID));
    }

    private static Message<byte[]> connect(String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer " + token);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
