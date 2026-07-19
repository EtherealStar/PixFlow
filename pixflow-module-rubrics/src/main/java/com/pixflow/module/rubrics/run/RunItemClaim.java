package com.pixflow.module.rubrics.run;

import java.time.Instant;

public record RunItemClaim(long itemId, long epoch, String owner, Instant leaseExpiresAt) {

    public RunItemClaim {
        if (itemId <= 0 || epoch <= 0) {
            throw new IllegalArgumentException("claim item id and epoch must be positive");
        }
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("claim owner must not be blank");
        }
        if (leaseExpiresAt == null) {
            throw new IllegalArgumentException("claim lease expiry is required");
        }
    }
}
