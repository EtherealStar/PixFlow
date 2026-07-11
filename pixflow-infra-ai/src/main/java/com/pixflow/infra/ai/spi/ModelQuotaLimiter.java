package com.pixflow.infra.ai.spi;

import com.pixflow.infra.ai.model.ModelRole;
import java.time.Duration;

public interface ModelQuotaLimiter {
    QuotaDecision tryConsume(ModelRole role, String provider, String quotaGroup, long cost);

    record QuotaDecision(boolean allowed, long remaining, Duration retryAfter) {
    }
}
