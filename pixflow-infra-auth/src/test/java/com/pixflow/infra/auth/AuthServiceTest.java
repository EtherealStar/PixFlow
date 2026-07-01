package com.pixflow.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.auth.config.AuthProperties;
import com.pixflow.infra.auth.crypto.PasswordHasher;
import com.pixflow.infra.auth.error.AuthErrorCode;
import com.pixflow.infra.auth.error.AuthException;
import com.pixflow.infra.auth.persistence.UserAccountEntity;
import com.pixflow.infra.auth.persistence.UserAccountMapper;
import com.pixflow.infra.auth.persistence.UserAccountStatus;
import com.pixflow.infra.auth.service.AuthService;
import com.pixflow.infra.auth.service.LoginRequest;
import com.pixflow.infra.auth.service.RegisterRequest;
import com.pixflow.infra.auth.token.JwtTokenService;
import com.pixflow.infra.auth.token.RefreshTokenGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);
    private UserAccountMapper userMapper;
    private InMemoryAuthSessionStore sessionStore;
    private InMemoryAccessTokenBlacklist blacklist;
    private AuthService authService;
    private UserAccountEntity storedUser;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setSecret("test-secret-with-more-than-32-bytes");
        properties.getPassword().setBcryptStrength(10);
        userMapper = mock(UserAccountMapper.class);
        sessionStore = new InMemoryAuthSessionStore();
        blacklist = new InMemoryAccessTokenBlacklist();
        PasswordHasher passwordHasher = new PasswordHasher(10);
        authService = new AuthService(
                userMapper,
                passwordHasher,
                new JwtTokenService(properties, new ObjectMapper(), clock),
                new RefreshTokenGenerator(),
                sessionStore,
                blacklist,
                new NoopLoginThrottleService(),
                properties,
                clock);
        doAnswer(invocation -> {
            storedUser = invocation.getArgument(0);
            storedUser.setId(1L);
            return 1;
        }).when(userMapper).insert(any(UserAccountEntity.class));
        when(userMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> storedUser);
        when(userMapper.selectById(1L)).thenAnswer(invocation -> storedUser);
    }

    @Test
    void registerCreatesActiveUserAndTokens() {
        var response = authService.register(new RegisterRequest(" Alice_1 ", "password-1", "Alice"));

        assertThat(storedUser.getUsername()).isEqualTo("alice_1");
        assertThat(storedUser.getStatus()).isEqualTo(UserAccountStatus.ACTIVE.name());
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(sessionStore.size()).isEqualTo(1);
    }

    @Test
    void loginRejectsBadPassword() {
        authService.register(new RegisterRequest("alice", "password-1", "Alice"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", "wrong-password"), "127.0.0.1"))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS));
    }

    @Test
    void refreshRotatesRefreshSession() {
        var registered = authService.register(new RegisterRequest("alice", "password-1", "Alice"));

        var refreshed = authService.refresh(registered.refreshToken());

        assertThat(refreshed.accessToken()).isNotEqualTo(registered.accessToken());
        assertThat(refreshed.refreshToken()).isNotEqualTo(registered.refreshToken());
        assertThat(sessionStore.size()).isEqualTo(1);
    }

    @Test
    void logoutRevokesAccessTokenAndRefreshSession() {
        var registered = authService.register(new RegisterRequest("alice", "password-1", "Alice"));

        authService.logout(registered.refreshToken(), registered.accessToken());

        assertThat(sessionStore.size()).isZero();
        assertThatThrownBy(() -> authService.authenticateAccessToken(registered.accessToken()))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_TOKEN_REVOKED));
    }

    @Test
    void disabledAccountCannotAuthenticate() {
        var registered = authService.register(new RegisterRequest("alice", "password-1", "Alice"));
        storedUser.setStatus(UserAccountStatus.DISABLED.name());

        assertThatThrownBy(() -> authService.authenticateAccessToken(registered.accessToken()))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_ACCOUNT_DISABLED));
        verify(userMapper).selectById(1L);
    }
}
