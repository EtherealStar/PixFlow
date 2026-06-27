package com.pixflow.harness.permission.token;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.contracts.confirmation.ConfirmationLevel;
import com.pixflow.contracts.confirmation.ConfirmationToken;
import com.pixflow.contracts.confirmation.ConfirmationTokenStore;
import com.pixflow.contracts.confirmation.TokenClaims;
import com.pixflow.harness.permission.PermissionContext;
import com.pixflow.harness.permission.PermissionErrorCode;
import com.pixflow.harness.permission.PermissionSubject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 令牌签发与校验服务。
 *
 * <p>行为留在 permission；令牌形状与存储 SPI 来自 contracts，避免 permission 直接依赖 Redis 实现。</p>
 */
public class ConfirmationTokenService {
    private final ConfirmationTokenStore store;
    private final Clock clock;

    public ConfirmationTokenService(ConfirmationTokenStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ConfirmationToken issue(TokenClaims claims) {
        Objects.requireNonNull(claims, "claims");
        Instant now = clock.instant();
        if (!claims.expiresAt().isAfter(now)) {
            throw new PixFlowException(
                    PermissionErrorCode.CONFIRMATION_TOKEN_EXPIRED,
                    "确认令牌已过期");
        }
        String tokenId = UUID.randomUUID().toString();
        Duration ttl = Duration.between(now, claims.expiresAt());
        store.save(tokenId, claims, ttl);
        return new ConfirmationToken(tokenId);
    }

    public void verifyAndConsume(
            ConfirmationToken token, PermissionSubject subject, PermissionContext context, int bulkThreshold) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(context, "context");

        // consume 必须由实现方保证原子读删；这里拿到 claims 即表示令牌已被消费。
        TokenClaims claims = store.consume(token.tokenId())
                .orElseThrow(() -> new PixFlowException(
                        PermissionErrorCode.CONFIRMATION_TOKEN_EXPIRED, "确认令牌已失效"));

        Instant now = clock.instant();
        if (!claims.expiresAt().isAfter(now)) {
            throw new PixFlowException(PermissionErrorCode.CONFIRMATION_TOKEN_EXPIRED, "确认令牌已过期");
        }
        if (claims.action() != subject.confirmationAction()) {
            throw new PixFlowException(PermissionErrorCode.CONFIRMATION_TOKEN_INVALID, "确认动作不匹配");
        }
        if (!claims.conversationId().equals(context.conversationId())) {
            throw new PixFlowException(PermissionErrorCode.CONFIRMATION_TOKEN_INVALID, "会话不匹配");
        }
        if (!claims.packageId().equals(subject.packageId())) {
            throw new PixFlowException(PermissionErrorCode.CONFIRMATION_TOKEN_INVALID, "素材包不匹配");
        }
        if (!claims.payloadHash().equals(subject.payloadHash())) {
            throw new PixFlowException(PermissionErrorCode.CONFIRMATION_PAYLOAD_MISMATCH, "载荷已变化，需要重新确认");
        }
        if (claims.expectedCount() != subject.actualCount()) {
            throw new PixFlowException(PermissionErrorCode.CONFIRMATION_COUNT_MISMATCH, "执行数量已变化，需要重新确认");
        }
        if (subject.actualCount() > bulkThreshold && claims.level() != ConfirmationLevel.BULK) {
            throw new PixFlowException(PermissionErrorCode.BULK_CONFIRMATION_REQUIRED, "超过批量阈值，需要批量确认");
        }
    }
}
