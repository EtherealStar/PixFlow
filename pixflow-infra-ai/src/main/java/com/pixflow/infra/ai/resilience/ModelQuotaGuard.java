package com.pixflow.infra.ai.resilience;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.ai.config.AiProperties;
import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.infra.ai.model.ResolvedModel;
import com.pixflow.infra.ai.observability.AiMetrics;
import com.pixflow.infra.ai.spi.ModelQuotaLimiter;
import java.util.Map;
import java.util.Objects;

public final class ModelQuotaGuard {
    private final ModelQuotaLimiter limiter;

    private final AiProperties properties;

    private final AiMetrics metrics;

    public ModelQuotaGuard(ModelQuotaLimiter limiter, AiProperties properties, AiMetrics metrics) {
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public void tryConsume(ResolvedModel model) {
        AiProperties.Quota quota = properties.quota(model.role());
        ModelQuotaLimiter.QuotaDecision decision;
        try {
            decision = limiter.tryConsume(
                    model.role(), model.provider(), quota.quotaGroup(), quota.costPerAttempt());
        } catch (RuntimeException ex) {
            metrics.recordQuota(model.role(), model.provider(), AiMetrics.QuotaResult.ERROR);
            throw new PixFlowException(
                    AiErrorCode.MODEL_QUOTA_UNAVAILABLE,
                    "模型出站额度服务不可用",
                    ex,
                    Map.of("role", model.role().name(), "provider", model.provider()),
                    RecoveryHint.RETRY,
                    null,
                    null);
        }
        if (!decision.allowed()) {
            metrics.recordQuota(model.role(), model.provider(), AiMetrics.QuotaResult.REJECTED);
            throw new PixFlowException(
                    AiErrorCode.MODEL_LOCAL_RATE_LIMITED,
                    "模型出站额度暂时不足",
                    null,
                    Map.of("role", model.role().name(), "provider", model.provider()),
                    RecoveryHint.RETRY,
                    decision.retryAfter(),
                    null);
        }
        metrics.recordQuota(model.role(), model.provider(), AiMetrics.QuotaResult.ALLOWED);
    }
}
