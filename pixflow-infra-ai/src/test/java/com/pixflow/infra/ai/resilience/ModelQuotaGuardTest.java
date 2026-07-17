package com.pixflow.infra.ai.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.ai.config.AiProperties;
import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.infra.ai.model.ModelCapability;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.model.ResolvedModel;
import com.pixflow.infra.ai.observability.AiMetrics;
import com.pixflow.infra.ai.spi.ModelQuotaLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelQuotaGuardTest {
    private static final ResolvedModel MODEL = new ResolvedModel(
            ModelRole.PRIMARY_CHAT,
            "custom",
            "model",
            ModelCapability.CHAT,
            null,
            null,
            Duration.ofSeconds(1));

    @Test
    void recordsAllowedRejectedAndErrorWithoutHighCardinalityTags() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AiMetrics metrics = new AiMetrics(meters);

        guard((role, provider, group, cost) -> decision(true), metrics).tryConsume(MODEL);
        assertThatThrownBy(() -> guard(
                (role, provider, group, cost) -> new ModelQuotaLimiter.QuotaDecision(
                        false, 0, Duration.ofSeconds(2)), metrics).tryConsume(MODEL))
                .isInstanceOfSatisfying(PixFlowException.class, error -> {
                    assertThat(error.code()).isEqualTo(AiErrorCode.MODEL_LOCAL_RATE_LIMITED);
                    assertThat(error.retryAfter()).isEqualTo(Duration.ofSeconds(2));
                });
        assertThatThrownBy(() -> guard((role, provider, group, cost) -> {
            throw new IllegalStateException("redis unavailable");
        }, metrics).tryConsume(MODEL))
                .isInstanceOfSatisfying(PixFlowException.class,
                        error -> assertThat(error.code()).isEqualTo(AiErrorCode.MODEL_QUOTA_UNAVAILABLE));

        assertThat(meters.get("pixflow.ai.quota").meters()).hasSize(3);
        assertThat(meters.get("pixflow.ai.quota")
                .tags("role", "PRIMARY_CHAT", "provider", "custom", "result", "allowed")
                .counter().count()).isEqualTo(1.0d);
        assertThat(meters.get("pixflow.ai.quota")
                .tags("role", "PRIMARY_CHAT", "provider", "custom", "result", "rejected")
                .counter().count()).isEqualTo(1.0d);
        assertThat(meters.get("pixflow.ai.quota")
                .tags("role", "PRIMARY_CHAT", "provider", "custom", "result", "error")
                .counter().count()).isEqualTo(1.0d);
    }

    private static ModelQuotaGuard guard(ModelQuotaLimiter limiter, AiMetrics metrics) {
        AiProperties properties = new AiProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new AiProperties.QuotaSettings(Map.of(
                        ModelRole.PRIMARY_CHAT,
                        new AiProperties.Quota(
                                "chat", 10, 10, Duration.ofSeconds(1), Duration.ofMinutes(1), 1))));
        return new ModelQuotaGuard(limiter, properties, metrics);
    }

    private static ModelQuotaLimiter.QuotaDecision decision(boolean allowed) {
        return new ModelQuotaLimiter.QuotaDecision(allowed, 9, Duration.ZERO);
    }
}
