package com.pixflow.infra.thirdparty.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.thirdparty")
public record ThirdPartyProperties(BgRemoval bgRemoval, Map<String, Provider> providers, Http http, Resilience resilience) {

    public ThirdPartyProperties {
        bgRemoval = bgRemoval == null ? new BgRemoval("removebg") : bgRemoval;
        providers = providers == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(providers));
        http = http == null ? new Http(Duration.ofSeconds(30), Duration.ofSeconds(10)) : http;
        resilience = resilience == null ? new Resilience(3, Duration.ofMillis(500), Duration.ofSeconds(8), Duration.ofSeconds(30), 16, 16) : resilience;
    }

    public Provider provider(String providerId) {
        return providers.get(providerId);
    }

    public record BgRemoval(String defaultProvider) {
    }

    public record Http(Duration connectTimeout, Duration readTimeout) {
    }

    public record Resilience(int maxAttempts, Duration baseDelay, Duration maxDelay, Duration timeout, int bulkheadMaxConcurrent, int rateLimitLimitForPeriod) {
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
            ResilienceOverride resilienceOverride) {
        public Provider {
            auth = auth == null ? new Auth("none", Map.of()) : auth;
            request = request == null ? new Request("multipart-bytes", null, null, null, null, null) : request;
            response = response == null ? new Response("binary-body", null, null, null) : response;
        }
    }

    public record Auth(String type, Map<String, String> properties) {
        public Auth {
            properties = properties == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(properties));
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
}
