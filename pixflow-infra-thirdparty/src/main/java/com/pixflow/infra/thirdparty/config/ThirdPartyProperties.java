package com.pixflow.infra.thirdparty.config;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.thirdparty")
public record ThirdPartyProperties(
        BgRemoval bgRemoval,
        Map<String, Provider> providers,
        Http http,
        Resilience resilience,
        Map<String, Map<String, OutboundQuota>> outboundQuota) {

    public ThirdPartyProperties {
        bgRemoval = bgRemoval == null ? new BgRemoval("removebg") : bgRemoval;
        providers = immutableCopy(providers);
        http = http == null ? new Http(Duration.ofSeconds(30), Duration.ofSeconds(10)) : http;
        resilience = resilience == null
                ? new Resilience(3, Duration.ofMillis(500), Duration.ofSeconds(8), Duration.ofSeconds(30), 16)
                : resilience;
        outboundQuota = immutableNestedCopy(outboundQuota);
    }

    public Provider provider(String providerId) {
        return providers.get(providerId);
    }

    public record BgRemoval(String defaultProvider) {
    }

    public record Http(Duration connectTimeout, Duration readTimeout) {
    }

    public record Resilience(
            int maxAttempts,
            Duration baseDelay,
            Duration maxDelay,
            Duration timeout,
            int bulkheadMaxConcurrent) {
    }

    public record OutboundQuota(
            long capacity,
            long refillTokens,
            Duration refillPeriod,
            Duration idleTtl,
            long costPerAttempt) {
        public OutboundQuota {
            if (capacity <= 0 || refillTokens <= 0 || costPerAttempt <= 0 || costPerAttempt > capacity) {
                throw new IllegalArgumentException("第三方出站额度与单次权重必须为正，且权重不能超过容量");
            }
            requirePositive(refillPeriod, "refillPeriod");
            requirePositive(idleTtl, "idleTtl");
        }
    }

    public OutboundQuota outboundQuota(String providerId, String api) {
        Map<String, OutboundQuota> providerQuota = outboundQuota.get(providerId);
        OutboundQuota quota = providerQuota == null ? null : providerQuota.get(api);
        if (quota == null) {
            throw new IllegalStateException("未配置第三方出站额度: " + providerId + "/" + api);
        }
        return quota;
    }

    public record Provider(
            String type,
            boolean enabled,
            String endpoint,
            Auth auth,
            Request request,
            Response response,
            Polling polling,
            Limits limits,
            ResilienceOverride resilienceOverride,
            String modelType) {
        public Provider {
            auth = auth == null ? new Auth("none", Map.of()) : auth;
            request = request == null ? new Request("multipart-bytes", null, null, null, null, null) : request;
            response = response == null ? new Response("binary-body", null, null, null) : response;
        }
    }

    public record Auth(String type, Map<String, String> properties) {
        public Auth {
            properties = immutableCopy(properties);
        }
    }

    public record Request(
            String mode,
            String fileField,
            String imageField,
            String urlField,
            String resultField,
            String resultUrlField) {
    }

    public record Response(
            String mode,
            String imageField,
            String resultUrlField,
            String creditsField) {
    }

    public record Polling(
            String submitPath,
            String statusPath,
            String resultPath,
            String statusField,
            String successStatus,
            String failedStatus,
            String resultField,
            String resultUrlField,
            Duration timeout,
            Duration interval) {
    }

    public record Limits(Integer maxImageBytes, Integer maxRequestsPerMinute) {
    }

    public record ResilienceOverride(Integer maxAttempts, Duration timeout, Duration baseDelay) {
    }

    private static <K, V> Map<K, V> immutableCopy(Map<K, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, Map<String, OutboundQuota>> immutableNestedCopy(
            Map<String, Map<String, OutboundQuota>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, OutboundQuota>> copy = new LinkedHashMap<>();
        source.forEach((provider, quotas) -> copy.put(provider, immutableCopy(quotas)));
        return Collections.unmodifiableMap(copy);
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " 必须大于 0");
        }
    }
}
