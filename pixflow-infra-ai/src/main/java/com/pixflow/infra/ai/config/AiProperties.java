package com.pixflow.infra.ai.config;

import com.pixflow.infra.ai.model.ModelCapability;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.common.error.PixFlowException;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ai 模块配置。
 */
@ConfigurationProperties(prefix = "pixflow.ai")
public record AiProperties(
        String defaultProvider,
        DashScope dashscope,
        Map<String, ProviderConfig> providers,
        Roles roles,
        Retry retry,
        Duration timeout,
        Concurrency concurrency,
        Map<ModelRole, Quota> quotas) {

    public AiProperties {
        defaultProvider = defaultProvider == null ? "dashscope" : defaultProvider;
        dashscope = dashscope == null ? new DashScope(null, "https://dashscope.aliyuncs.com") : dashscope;
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        roles = roles == null ? Roles.defaults() : roles;
        retry = retry == null ? new Retry(10, Duration.ofMillis(500), Duration.ofSeconds(32), 0.25d) : retry;
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        concurrency = concurrency == null ? new Concurrency(16, 8, 4) : concurrency;
        quotas = quotas == null ? Map.of() : Map.copyOf(quotas);
    }

    public record DashScope(String apiKey, String baseUrl) {
    }

    public record ProviderConfig(String apiKey, String baseUrl) {
    }

    public ProviderConfig provider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return null;
        }
        ProviderConfig direct = providers.get(providerId);
        if (direct != null) {
            return direct;
        }
        return providers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(providerId))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    public record Retry(int maxRetries, Duration baseDelay, Duration maxDelay, double jitterRatio) {
    }

    public record Concurrency(int primaryChat, int vision, int imagegen) {
    }

    public record Quota(
            String quotaGroup,
            long capacity,
            long refillTokens,
            Duration refillPeriod,
            Duration idleTtl,
            long costPerAttempt) {
        public Quota {
            if (quotaGroup == null || quotaGroup.isBlank()) {
                throw new IllegalArgumentException("quotaGroup 不能为空");
            }
            if (capacity <= 0 || refillTokens <= 0 || costPerAttempt <= 0 || costPerAttempt > capacity) {
                throw new IllegalArgumentException("模型额度与单次权重必须为正，且权重不能超过容量");
            }
            requirePositive(refillPeriod, "refillPeriod");
            requirePositive(idleTtl, "idleTtl");
        }
    }

    public Quota quota(ModelRole role) {
        Quota quota = quotas.get(role);
        if (quota == null) {
            throw new PixFlowException(
                    com.pixflow.infra.ai.error.AiErrorCode.MODEL_CONFIGURATION_ERROR,
                    "未配置模型出站额度: " + role,
                    null,
                    Map.of("role", role.name()),
                    com.pixflow.common.error.RecoveryHint.TERMINATE,
                    null,
                    null);
        }
        return quota;
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
    }

    public record Roles(
            RoleConfig primaryChat,
            RoleConfig vision,
            RoleConfig rubricsJudgeText,
            RoleConfig rubricsJudgeVision,
            RoleConfig imagegen,
            RoleConfig embedding,
            RoleConfig rerank) {
        public static Roles defaults() {
            return new Roles(
                    new RoleConfig("dashscope", "qwen-max", ModelCapability.CHAT, 0.3d, 4096, null),
                    new RoleConfig("dashscope", "qwen-vl-max", ModelCapability.VISION, null, null, null),
                    null,
                    null,
                    new RoleConfig("dashscope", "wanx-v1", ModelCapability.IMAGEGEN, null, null, null),
                    new RoleConfig("dashscope", "text-embedding-v3", ModelCapability.EMBEDDING, null, null, null),
                    new RoleConfig("dashscope", "gte-rerank", ModelCapability.RERANK, null, null, null));
        }
    }

    public record RoleConfig(
            String provider,
            String model,
            ModelCapability capability,
            Double temperature,
            Integer maxTokens,
            Duration timeout) {
    }
}
