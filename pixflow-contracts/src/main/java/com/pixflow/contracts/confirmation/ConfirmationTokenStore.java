package com.pixflow.contracts.confirmation;

import java.time.Duration;
import java.util.Optional;

/**
 * 确认令牌存储 SPI。
 *
 * <p>实现方必须保证 {@link #consume(String)} 是原子读取并删除，并发下只能有一次消费成功。</p>
 */
public interface ConfirmationTokenStore {
    void save(String tokenId, TokenClaims claims, Duration ttl);

    /**
     * 原子读取并删除令牌 claims，避免确认令牌被重放。
     */
    Optional<TokenClaims> consume(String tokenId);
}
