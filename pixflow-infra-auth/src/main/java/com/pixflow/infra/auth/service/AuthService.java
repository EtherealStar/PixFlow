package com.pixflow.infra.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.infra.auth.config.AuthProperties;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.crypto.PasswordHasher;
import com.pixflow.infra.auth.error.AuthErrorCode;
import com.pixflow.infra.auth.error.AuthException;
import com.pixflow.infra.auth.identity.AdministratorEligibility;
import com.pixflow.infra.auth.identity.AdministratorIneligibleException;
import com.pixflow.infra.auth.identity.UsernameNormalizer;
import com.pixflow.infra.auth.persistence.UserAccountEntity;
import com.pixflow.infra.auth.persistence.UserAccountMapper;
import com.pixflow.infra.auth.session.AccessTokenBlacklist;
import com.pixflow.infra.auth.session.AuthSession;
import com.pixflow.infra.auth.session.AuthSessionStore;
import com.pixflow.infra.auth.session.TokenHashing;
import com.pixflow.infra.auth.throttle.LoginThrottleService;
import com.pixflow.infra.auth.token.AccessTokenClaims;
import com.pixflow.infra.auth.token.IssuedAccessToken;
import com.pixflow.infra.auth.token.JwtTokenService;
import com.pixflow.infra.auth.token.RefreshToken;
import com.pixflow.infra.auth.token.RefreshTokenGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class AuthService {
    private final UserAccountMapper userMapper;

    private final PasswordHasher passwordHasher;

    private final JwtTokenService jwtTokenService;

    private final RefreshTokenGenerator refreshTokenGenerator;

    private final AuthSessionStore sessionStore;

    private final AccessTokenBlacklist blacklist;

    private final LoginThrottleService throttleService;

    private final AdministratorEligibility administratorEligibility;

    private final AuthProperties properties;

    private final Clock clock;

    public AuthService(
            UserAccountMapper userMapper,
            PasswordHasher passwordHasher,
            JwtTokenService jwtTokenService,
            RefreshTokenGenerator refreshTokenGenerator,
            AuthSessionStore sessionStore,
            AccessTokenBlacklist blacklist,
            LoginThrottleService throttleService,
            AdministratorEligibility administratorEligibility,
            AuthProperties properties,
            Clock clock) {
        this.userMapper = userMapper;
        this.passwordHasher = passwordHasher;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.sessionStore = sessionStore;
        this.blacklist = blacklist;
        this.throttleService = throttleService;
        this.administratorEligibility = administratorEligibility;
        this.properties = properties;
        this.clock = clock;
    }

    public AuthTokenResponse login(LoginRequest request, String ipAddress) {
        if (request == null) {
            throw new AuthException(AuthErrorCode.AUTH_INVALID_CREDENTIALS, "登录请求不能为空");
        }
        String username = UsernameNormalizer.normalize(request.username());
        String throttleUsername = throttleUsername(username);
        throttleService.assertAllowed(throttleUsername, ipAddress);
        if (!UsernameNormalizer.isValid(username)) {
            throttleService.recordFailureAndAssert(throttleUsername, ipAddress);
            throw new AuthException(AuthErrorCode.AUTH_INVALID_CREDENTIALS, "用户名或密码错误");
        }
        UserAccountEntity entity = findByUsername(username).orElse(null);
        if (entity == null || !passwordHasher.matches(request.password(), entity.getPasswordHash())) {
            throttleService.recordFailureAndAssert(throttleUsername, ipAddress);
            throw new AuthException(AuthErrorCode.AUTH_INVALID_CREDENTIALS, "用户名或密码错误");
        }
        AuthPrincipal principal;
        try {
            principal = requireEligible(
                    entity.getId(), AuthErrorCode.AUTH_INVALID_CREDENTIALS, "用户名或密码错误");
        } catch (AuthException ex) {
            // Historical Account 与禁用账号仍计入同一登录防护，不向客户端暴露资格差异。
            throttleService.recordFailureAndAssert(throttleUsername, ipAddress);
            throw ex;
        }
        entity.setLastLoginAt(clock.instant());
        entity.setUpdatedAt(clock.instant());
        userMapper.updateById(entity);
        throttleService.clearUsername(throttleUsername);
        return issueTokens(principal);
    }

    public AuthTokenResponse refresh(String refreshTokenValue) {
        RefreshTokenParts parts = parseRefreshToken(refreshTokenValue);
        AuthSession session = sessionStore.consume(parts.jwtId())
                .orElseThrow(() -> new AuthException(AuthErrorCode.AUTH_REFRESH_EXPIRED, "refresh session 已失效"));
        if (!TokenHashing.sha256(parts.token()).equals(session.tokenHash())) {
            throw new AuthException(AuthErrorCode.AUTH_REFRESH_INVALID, "refresh token 校验失败");
        }
        if (session.expiresAt().isBefore(clock.instant())) {
            throw new AuthException(AuthErrorCode.AUTH_REFRESH_EXPIRED, "refresh token 已过期");
        }
        AuthPrincipal principal = requireEligible(
                session.userId(), AuthErrorCode.AUTH_REFRESH_INVALID, "refresh token 校验失败");
        return issueTokens(principal);
    }

    public void logout(String refreshTokenValue, String accessTokenValue) {
        if (refreshTokenValue != null && !refreshTokenValue.isBlank()) {
            try {
                sessionStore.delete(parseRefreshToken(refreshTokenValue).jwtId());
            } catch (AuthException ignored) {
                // logout is intentionally idempotent for stale cookies.
            }
        }
        if (accessTokenValue != null && !accessTokenValue.isBlank()) {
            try {
                AccessTokenClaims claims = jwtTokenService.parse(accessTokenValue);
                long ttlSeconds = jwtTokenService.remainingTtlSeconds(claims);
                if (ttlSeconds > 0) {
                    blacklist.revoke(claims.jwtId(), Duration.ofSeconds(ttlSeconds));
                }
            } catch (AuthException ignored) {
                // Invalid access tokens do not prevent cookie clearing.
            }
        }
    }

    public AuthPrincipal authenticateAccessToken(String accessTokenValue) {
        AccessTokenClaims claims = jwtTokenService.parse(accessTokenValue);
        if (blacklist.isRevoked(claims.jwtId())) {
            throw new AuthException(AuthErrorCode.AUTH_TOKEN_REVOKED, "access token 已失效");
        }
        AuthPrincipal principal = requireEligible(
                claims.userId(), AuthErrorCode.AUTH_TOKEN_INVALID, "access token 校验失败");
        if (!claims.username().equals(principal.username())) {
            throw new AuthException(AuthErrorCode.AUTH_TOKEN_INVALID, "access token 校验失败");
        }
        return principal;
    }

    private AuthTokenResponse issueTokens(AuthPrincipal principal) {
        IssuedAccessToken accessToken = jwtTokenService.issue(principal.userId(), principal.username());
        RefreshToken refreshToken = refreshTokenGenerator.generate();
        Instant now = clock.instant();
        Instant refreshExpiresAt = now.plus(properties.getRefresh().getTtl());
        sessionStore.save(
                new AuthSession(
                        refreshToken.jwtId(),
                        principal.userId(),
                        principal.username(),
                        TokenHashing.sha256(refreshToken.token()),
                        now,
                        refreshExpiresAt),
                properties.getRefresh().getTtl());
        return new AuthTokenResponse(
                accessToken.token(),
                accessToken.expiresAt(),
                UserView.from(principal),
                encodeRefreshCookieValue(refreshToken),
                refreshExpiresAt);
    }

    private Optional<UserAccountEntity> findByUsername(String username) {
        return Optional.ofNullable(userMapper.selectOne(new LambdaQueryWrapper<UserAccountEntity>()
                .eq(UserAccountEntity::getUsername, username)
                .last("limit 1")));
    }

    private AuthPrincipal requireEligible(long userId, AuthErrorCode errorCode, String message) {
        try {
            return administratorEligibility.requireEligible(userId);
        } catch (AdministratorIneligibleException ex) {
            throw new AuthException(errorCode, message);
        }
    }

    private static String throttleUsername(String username) {
        if (username == null || username.isBlank()) {
            return "invalid";
        }
        return username.substring(0, Math.min(username.length(), 64));
    }

    private static String encodeRefreshCookieValue(RefreshToken refreshToken) {
        return refreshToken.jwtId() + "." + refreshToken.token();
    }

    private static RefreshTokenParts parseRefreshToken(String value) {
        if (value == null || value.isBlank()) {
            throw new AuthException(AuthErrorCode.AUTH_REFRESH_INVALID, "缺少 refresh token");
        }
        int delimiter = value.indexOf('.');
        if (delimiter <= 0 || delimiter == value.length() - 1) {
            throw new AuthException(AuthErrorCode.AUTH_REFRESH_INVALID, "refresh token 格式非法");
        }
        return new RefreshTokenParts(value.substring(0, delimiter), value.substring(delimiter + 1));
    }

    private record RefreshTokenParts(String jwtId, String token) {
    }
}
