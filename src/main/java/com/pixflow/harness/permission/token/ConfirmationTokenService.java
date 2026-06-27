package com.pixflow.harness.permission.token;

import com.pixflow.common.error.PixFlowException;
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
