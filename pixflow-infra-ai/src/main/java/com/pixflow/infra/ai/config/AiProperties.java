package com.pixflow.infra.ai.config;

import com.pixflow.infra.ai.model.ModelCapability;
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
        Concurrency concurrency) {

    public AiProperties {
        defaultProvider = defaultProvider == null ? "dashscope" : defaultProvider;
        dashscope = dashscope == null ? new DashScope(null, "https://dashscope.aliyuncs.com") : dashscope;
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        roles = roles == null ? Roles.defaults() : roles;
        retry = retry == null ? new Retry(10, Duration.ofMillis(500), Duration.ofSeconds(32), 0.25d) : retry;
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        concurrency = concurrency == null ? new Concurrency(16, 8, 4) : concurrency;
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

    public record Roles(RoleConfig primaryChat, RoleConfig vision, RoleConfig imagegen, RoleConfig embedding, RoleConfig rerank) {
        public static Roles defaults() {
            return new Roles(
                    new RoleConfig("dashscope", "qwen-max", ModelCapability.CHAT, 0.3d, 4096, null),
                    new RoleConfig("dashscope", "qwen-vl-max", ModelCapability.VISION, null, null, null),
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
