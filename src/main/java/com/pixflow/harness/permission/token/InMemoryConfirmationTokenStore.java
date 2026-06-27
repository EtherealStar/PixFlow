package com.pixflow.harness.permission.token;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用内存令牌存储，便于验证单次消费语义。
 */
public class InMemoryConfirmationTokenStore implements ConfirmationTokenStore {
    private final ConcurrentHashMap<String, StoredToken> tokens = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryConfirmationTokenStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void save(String tokenId, TokenClaims claims, Duration ttl) {
        Instant expiresAt = clock.instant().plus(ttl);
        tokens.put(tokenId, new StoredToken(claims, expiresAt));
    }

    @Override
    public Optional<TokenClaims> consume(String tokenId) {
        StoredToken stored = tokens.remove(tokenId);
        if (stored == null) {
            return Optional.empty();
        }
        if (!stored.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(stored.claims());
    }

    private record StoredToken(TokenClaims claims, Instant expiresAt) {
    }
}
