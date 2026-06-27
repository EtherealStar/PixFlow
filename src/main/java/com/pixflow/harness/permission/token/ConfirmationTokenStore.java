package com.pixflow.harness.permission.token;

import java.time.Duration;
import java.util.Optional;

public interface ConfirmationTokenStore {
    void save(String tokenId, TokenClaims claims, Duration ttl);

    Optional<TokenClaims> consume(String tokenId);
}
