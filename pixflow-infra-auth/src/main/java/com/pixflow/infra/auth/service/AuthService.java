package com.pixflow.infra.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.infra.auth.config.AuthProperties;
import com.pixflow.infra.auth.context.AuthPrincipal;
import com.pixflow.infra.auth.crypto.PasswordHasher;
import com.pixflow.infra.auth.error.AuthErrorCode;
import com.pixflow.infra.auth.error.AuthException;
import com.pixflow.infra.auth.persistence.UserAccountEntity;
import com.pixflow.infra.auth.persistence.UserAccountMapper;
import com.pixflow.infra.auth.persistence.UserAccountStatus;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public class AuthService {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9_]{3,32}$");

    private final UserAccountMapper userMapper;
    private final PasswordHasher passwordHasher;
    private final JwtTokenService jwtTokenService;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final AuthSessionStore sessionStore;
    private final AccessTokenBlacklist blacklist;
    private final LoginThrottleService throttleService;
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
            AuthProperties properties,
            Clock clock) {
        this.userMapper = userMapper;
        this.passwordHasher = passwordHasher;
        this.jwtTokenService = jwtTokenService;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.sessionStore = sessionStore;
        this.blacklist = blacklist;
        this.throttleService = throttleService;
        this.properties = properties;
        this.clock = clock;
    }

    public AuthTokenResponse register(RegisterRequest request) {
        String username = normalizeUsername(request.username());
        validatePassword(request.password());
        if (findByUsername(username).isPresent()) {
            throw new AuthException(AuthErrorCode.AUTH_USERNAME_TAKEN, "用户名已被占用");
        }

        Instant now = clock.instant();
        UserAccountEntity entity = new UserAccountEntity();
        entity.setUsername(username);
        entity.setPasswordHash(passwordHasher.hash(request.password()));
        entity.setDisplayName(cleanDisplayName(request.displayName(), username));
        entity.setStatus(UserAccountStatus.ACTIVE.name());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setPasswordUpdatedAt(now);
        userMapper.insert(entity);
        return issueTokens(entity);
    }

    public AuthTokenResponse login(LoginRequest request, String ipAddress) {
        String username = normalizeUsername(request.username());
        throttleService.assertAllowed(username, ipAddress);
        UserAccountEntity entity = findByUsername(username).orElse(null);
        if (entity == null || !passwordHasher.matches(request.password(), entity.getPasswordHash())) {
            throttleService.recordFailure(username, ipAddress);
            throw new AuthException(AuthErrorCode.AUTH_INVALID_CREDENTIALS, "用户名或密码错误");
        }
        ensureActive(entity);
        entity.setLastLoginAt(clock.instant());
        entity.setUpdatedAt(clock.instant());
        userMapper.updateById(entity);
        throttleService.clear(username, ipAddress);
        return issueTokens(entity);
    }

    public AuthTokenResponse refresh(String refreshTokenValue) {
        RefreshTokenParts parts = parseRefreshToken(refreshTokenValue);
        AuthSession session = sessionStore.find(parts.jwtId())
                .orElseThrow(() -> new AuthException(AuthErrorCode.AUTH_REFRESH_EXPIRED, "refresh session 已失效"));
        if (!TokenHashing.sha256(parts.token()).equals(session.tokenHash())) {
            sessionStore.delete(parts.jwtId());
            throw new AuthException(AuthErrorCode.AUTH_REFRESH_INVALID, "refresh token 校验失败");
        }
        if (session.expiresAt().isBefore(clock.instant())) {
            sessionStore.delete(parts.jwtId());
            throw new AuthException(AuthErrorCode.AUTH_REFRESH_EXPIRED, "refresh token 已过期");
        }
        UserAccountEntity entity = userMapper.selectById(session.userId());
        if (entity == null) {
            sessionStore.delete(parts.jwtId());
            throw new AuthException(AuthErrorCode.AUTH_REFRESH_INVALID, "refresh token 用户不存在");
        }
        ensureActive(entity);
        sessionStore.delete(parts.jwtId());
        return issueTokens(entity);
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
                blacklist.revoke(claims.jwtId(), Duration.ofSeconds(jwtTokenService.remainingTtlSeconds(claims)));
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
        UserAccountEntity entity = userMapper.selectById(claims.userId());
        if (entity == null) {
            throw new AuthException(AuthErrorCode.AUTH_TOKEN_INVALID, "access token 用户不存在");
        }
        ensureActive(entity);
        return toPrincipal(entity);
    }

    public UserView me(AuthPrincipal principal) {
        return UserView.from(principal);
    }

    private AuthTokenResponse issueTokens(UserAccountEntity entity) {
        IssuedAccessToken accessToken = jwtTokenService.issue(entity.getId(), entity.getUsername());
        RefreshToken refreshToken = refreshTokenGenerator.generate();
        Instant now = clock.instant();
        Instant refreshExpiresAt = now.plus(properties.getRefresh().getTtl());
        sessionStore.save(
                new AuthSession(
                        refreshToken.jwtId(),
                        entity.getId(),
                        entity.getUsername(),
                        TokenHashing.sha256(refreshToken.token()),
                        now,
                        refreshExpiresAt),
                properties.getRefresh().getTtl());
        return new AuthTokenResponse(
                accessToken.token(),
                accessToken.expiresAt(),
                UserView.from(toPrincipal(entity)),
                encodeRefreshCookieValue(refreshToken),
                refreshExpiresAt);
    }

    private Optional<UserAccountEntity> findByUsername(String username) {
        return Optional.ofNullable(userMapper.selectOne(new LambdaQueryWrapper<UserAccountEntity>()
                .eq(UserAccountEntity::getUsername, username)
                .last("limit 1")));
    }

    private AuthPrincipal toPrincipal(UserAccountEntity entity) {
        return new AuthPrincipal(
                entity.getId(),
                entity.getUsername(),
                entity.getDisplayName(),
                entity.getStatus(),
                List.of("ROLE_USER"));
    }

    private void ensureActive(UserAccountEntity entity) {
        if (!UserAccountStatus.ACTIVE.name().equals(entity.getStatus())) {
            throw new AuthException(AuthErrorCode.AUTH_ACCOUNT_DISABLED, "账号已禁用");
        }
    }

    private static String normalizeUsername(String username) {
        if (username == null) {
            throw new AuthException(AuthErrorCode.AUTH_USERNAME_INVALID, "用户名不能为空");
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new AuthException(AuthErrorCode.AUTH_USERNAME_INVALID, "用户名只能包含 3-32 位小写字母、数字或下划线");
        }
        return normalized;
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new AuthException(AuthErrorCode.AUTH_PASSWORD_INVALID, "密码长度必须为 8-128 位");
        }
    }

    private static String cleanDisplayName(String displayName, String fallback) {
        if (displayName == null || displayName.isBlank()) {
            return fallback;
        }
        String trimmed = displayName.trim();
        return trimmed.length() > 128 ? trimmed.substring(0, 128) : trimmed;
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
