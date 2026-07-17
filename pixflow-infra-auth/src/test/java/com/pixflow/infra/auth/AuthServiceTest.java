package com.pixflow.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.auth.config.AuthProperties;
import com.pixflow.infra.auth.crypto.PasswordHasher;
import com.pixflow.infra.auth.error.AuthErrorCode;
import com.pixflow.infra.auth.error.AuthException;
import com.pixflow.infra.auth.identity.DatabaseAdministratorEligibility;
import com.pixflow.infra.auth.persistence.UserAccountEntity;
import com.pixflow.infra.auth.persistence.UserAccountMapper;
import com.pixflow.infra.auth.persistence.UserAccountStatus;
import com.pixflow.infra.auth.service.AuthService;
import com.pixflow.infra.auth.service.LoginRequest;
import com.pixflow.infra.auth.throttle.LoginThrottleService;
import com.pixflow.infra.auth.token.JwtTokenService;
import com.pixflow.infra.auth.token.RefreshTokenGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);
    private UserAccountMapper userMapper;
    private InMemoryAuthSessionStore sessionStore;
    private InMemoryAccessTokenBlacklist blacklist;
    private AuthProperties properties;
    private AuthService authService;
    private UserAccountEntity storedUser;

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
        properties.setAdminUsername("pixflow");
        properties.getJwt().setSecret("test-secret-with-more-than-32-bytes");
        properties.getPassword().setBcryptStrength(10);
        userMapper = mock(UserAccountMapper.class);
        sessionStore = new InMemoryAuthSessionStore();
        blacklist = new InMemoryAccessTokenBlacklist();
        PasswordHasher passwordHasher = new PasswordHasher(10);
        storedUser = account("pixflow", passwordHasher.hash("password-1"));
        when(userMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> storedUser);
        when(userMapper.selectById(1L)).thenAnswer(invocation -> storedUser);
        authService = new AuthService(
                userMapper,
                passwordHasher,
                new JwtTokenService(properties, new ObjectMapper(), clock),
                new RefreshTokenGenerator(),
                sessionStore,
                blacklist,
                new NoopLoginThrottleService(),
                new DatabaseAdministratorEligibility(userMapper, properties),
                properties,
                clock);
    }

    @Test
    void configuredAdministratorCanLogin() {
        var response = login();

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.user().username()).isEqualTo("pixflow");
        assertThat(sessionStore.size()).isEqualTo(1);
    }

    @Test
    void loginRejectsBadPassword() {
        assertThatThrownBy(() -> authService.login(
                        new LoginRequest("pixflow", "wrong-password"), "127.0.0.1"))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS));
    }

    @Test
    void invalidUsernameStillCountsTowardLoginThrottleWithBoundedKey() {
        LoginThrottleService throttleService = mock(LoginThrottleService.class);
        AuthService service = new AuthService(
                userMapper,
                new PasswordHasher(10),
                new JwtTokenService(properties, new ObjectMapper(), clock),
                new RefreshTokenGenerator(),
                sessionStore,
                blacklist,
                throttleService,
                new DatabaseAdministratorEligibility(userMapper, properties),
                properties,
                clock);
        String invalidUsername = "!".repeat(100);
        String boundedThrottleKey = "!".repeat(64);

        assertThatThrownBy(() -> service.login(
                        new LoginRequest(invalidUsername, "password-1"), "127.0.0.1"))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS));

        verify(throttleService).assertAllowed(boundedThrottleKey, "127.0.0.1");
        verify(throttleService).recordFailureAndAssert(boundedThrottleKey, "127.0.0.1");
    }

    @Test
    void historicalAccountWithCorrectPasswordCannotLogin() {
        storedUser.setUsername("historical");

        assertThatThrownBy(() -> authService.login(
                        new LoginRequest("historical", "password-1"), "127.0.0.1"))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS));
    }

    @Test
    void refreshRotatesRefreshSession() {
        var initial = login();

        var refreshed = authService.refresh(initial.refreshToken());

        assertThat(refreshed.accessToken()).isNotEqualTo(initial.accessToken());
        assertThat(refreshed.refreshToken()).isNotEqualTo(initial.refreshToken());
        assertThat(sessionStore.size()).isEqualTo(1);
    }

    @Test
    void refreshTokenCanOnlyBeConsumedOnceConcurrently() throws Exception {
        var initial = login();
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Runnable refresh = () -> {
                try {
                    start.await();
                    authService.refresh(initial.refreshToken());
                    successCount.incrementAndGet();
                } catch (Exception ex) {
                    failureCount.incrementAndGet();
                }
            };
            executor.submit(refresh);
            executor.submit(refresh);
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);
        assertThat(sessionStore.size()).isEqualTo(1);
    }

    @Test
    void changingConfiguredAdministratorInvalidatesRefresh() {
        var initial = login();
        properties.setAdminUsername("replacement");

        assertThatThrownBy(() -> authService.refresh(initial.refreshToken()))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_REFRESH_INVALID));
    }

    @Test
    void changingConfiguredAdministratorInvalidatesAccessToken() {
        var initial = login();
        properties.setAdminUsername("replacement");

        assertThatThrownBy(() -> authService.authenticateAccessToken(initial.accessToken()))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_TOKEN_INVALID));
    }

    @Test
    void logoutRevokesAccessTokenAndRefreshSession() {
        var initial = login();

        authService.logout(initial.refreshToken(), initial.accessToken());

        assertThat(sessionStore.size()).isZero();
        assertThatThrownBy(() -> authService.authenticateAccessToken(initial.accessToken()))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_TOKEN_REVOKED));
    }

    @Test
    void disabledAdministratorCannotAuthenticate() {
        var initial = login();
        storedUser.setStatus(UserAccountStatus.DISABLED.name());

        assertThatThrownBy(() -> authService.authenticateAccessToken(initial.accessToken()))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_TOKEN_INVALID));
    }

    private com.pixflow.infra.auth.service.AuthTokenResponse login() {
        return authService.login(new LoginRequest("pixflow", "password-1"), "127.0.0.1");
    }

    private static UserAccountEntity account(String username, String passwordHash) {
        UserAccountEntity account = new UserAccountEntity();
        account.setId(1L);
        account.setUsername(username);
        account.setPasswordHash(passwordHash);
        account.setDisplayName("PixFlow Administrator");
        account.setStatus(UserAccountStatus.ACTIVE.name());
        return account;
    }
}
