package com.pixflow.infra.thirdparty.bgremoval.provider.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.thirdparty.bgremoval.BackgroundRemovalRequest;
import com.pixflow.infra.thirdparty.bgremoval.BackgroundRemovalResult;
import com.pixflow.infra.thirdparty.bgremoval.ThirdPartyUsage;
import com.pixflow.infra.thirdparty.bgremoval.provider.BackgroundRemovalProvider;
import com.pixflow.infra.thirdparty.config.ThirdPartyProperties;
import com.pixflow.infra.thirdparty.error.ThirdPartyErrorCode;
import com.pixflow.infra.thirdparty.error.ThirdPartyErrorMapper;
import com.pixflow.infra.thirdparty.http.RestClientThirdPartyHttpInvoker;
import com.pixflow.infra.thirdparty.http.ThirdPartyAuthStrategy;
import com.pixflow.infra.thirdparty.http.ThirdPartyHttpRequest;
import com.pixflow.infra.thirdparty.http.ThirdPartyHttpResponse;
import com.pixflow.infra.thirdparty.http.ThirdPartyMutableRequest;
import com.pixflow.infra.thirdparty.observability.ThirdPartyMetrics;
import com.pixflow.infra.thirdparty.resilience.ThirdPartyCallContext;
import com.pixflow.infra.thirdparty.resilience.ThirdPartyCallTemplate;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriUtils;

public final class AsyncPollingBackgroundRemovalProvider implements BackgroundRemovalProvider {
    private static final String API = "bg-removal";

    private final String providerId;

    private final ThirdPartyProperties.Provider properties;

    private final ThirdPartyCallTemplate callTemplate;

    private final RestClientThirdPartyHttpInvoker httpInvoker;

    private final ThirdPartyAuthStrategy authStrategy;

    private final ThirdPartyErrorMapper errorMapper;

    private final ThirdPartyMetrics metrics;

    private final ObjectMapper objectMapper;

    public AsyncPollingBackgroundRemovalProvider(
            String providerId,
            ThirdPartyProperties.Provider properties,
            ThirdPartyCallTemplate callTemplate,
            RestClientThirdPartyHttpInvoker httpInvoker,
            ThirdPartyAuthStrategy authStrategy,
            ThirdPartyErrorMapper errorMapper,
            ThirdPartyMetrics metrics,
            ObjectMapper objectMapper) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.callTemplate = Objects.requireNonNull(callTemplate, "callTemplate");
        this.httpInvoker = Objects.requireNonNull(httpInvoker, "httpInvoker");
        this.authStrategy = Objects.requireNonNull(authStrategy, "authStrategy");
        this.errorMapper = Objects.requireNonNull(errorMapper, "errorMapper");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public BackgroundRemovalResult remove(BackgroundRemovalRequest request) {
        ThirdPartyProperties.Polling polling = properties.polling();
        if (polling == null) {
            throw new PixFlowException(
                    ThirdPartyErrorCode.THIRDPARTY_PROVIDER_NOT_CONFIGURED,
                    "polling configuration missing",
                    null,
                    Map.of(),
                    RecoveryHint.TERMINATE,
                    null,
                    null);
        }
        Instant deadline = Instant.now().plus(polling.timeout() == null ? Duration.ofSeconds(30) : polling.timeout());
        return callTemplate.execute(new ThirdPartyCallContext(API, providerId, Duration.ofSeconds(5)), () -> {
            String jobId = submit(request, polling);
            while (Instant.now().isBefore(deadline)) {
                BackgroundRemovalResult done = poll(jobId, polling);
                if (done != null) {
                    return done;
                }
                try {
                    Thread.sleep(polling.interval() == null ? 1000L : polling.interval().toMillis());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new PixFlowException(
                            ThirdPartyErrorCode.THIRDPARTY_PROVIDER_TIMEOUT,
                            "poll interrupted",
                            ex,
                            Map.of(),
                            RecoveryHint.RETRY,
                            polling.timeout(),
                            null);
                }
            }
            throw new PixFlowException(
                    ThirdPartyErrorCode.THIRDPARTY_PROVIDER_TIMEOUT,
                    "poll timeout",
                    null,
                    Map.of(),
                    RecoveryHint.RETRY,
                    polling.timeout(),
                    null);
        });
    }

    private String submit(BackgroundRemovalRequest request, ThirdPartyProperties.Polling polling) {
        ThirdPartyMutableRequest mutableRequest = new ThirdPartyMutableRequest(API, providerId);
        mutableRequest.method(HttpMethod.POST);
        mutableRequest.uri(resolve(polling.submitPath()));
        mutableRequest.body(request.image());
        mutableRequest.contentType(MediaType.APPLICATION_JSON);
        // Submit and polling calls for one provider must share the same authentication path.
        authStrategy.apply(mutableRequest, properties);
        ThirdPartyHttpRequest httpRequest = mutableRequest.toImmutable();
        ThirdPartyHttpResponse response = httpInvoker.exchange(httpRequest);
        if (response.statusCode() >= 400) {
            throw errorMapper.fromStatus(
                    org.springframework.http.HttpStatusCode.valueOf(response.statusCode()),
                    response.headers(),
                    new String(response.body(), StandardCharsets.UTF_8),
                    null);
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            return root.path("jobId").asText(root.path("id").asText());
        } catch (Exception ex) {
            throw errorMapper.invalidResponse("submit parse failed", ex);
        }
    }

    private BackgroundRemovalResult poll(String jobId, ThirdPartyProperties.Polling polling) {
        ThirdPartyMutableRequest mutableRequest = new ThirdPartyMutableRequest(API, providerId);
        mutableRequest.method(HttpMethod.GET);
        mutableRequest.uri(resolve(polling.statusPath().replace("{jobId}", encodePathSegment(jobId))));
        mutableRequest.body(new byte[0]);
        mutableRequest.contentType(MediaType.APPLICATION_JSON);
        authStrategy.apply(mutableRequest, properties);
        ThirdPartyHttpRequest httpRequest = mutableRequest.toImmutable();
        ThirdPartyHttpResponse response = httpInvoker.exchange(httpRequest);
        if (response.statusCode() >= 400) {
            throw errorMapper.fromStatus(
                    org.springframework.http.HttpStatusCode.valueOf(response.statusCode()),
                    response.headers(),
                    new String(response.body(), StandardCharsets.UTF_8),
                    null);
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            String status = root.path(firstNonBlank(polling.statusField(), "status")).asText();
            if (status.equalsIgnoreCase(polling.successStatus())) {
                String field = polling.resultField();
                String base64 = field == null ? null : root.path(field).asText(null);
                if (base64 == null && polling.resultUrlField() != null) {
                    String resultUrl = root.path(polling.resultUrlField()).asText(null);
                    return new BackgroundRemovalResult(
                            resultUrl == null ? new byte[0] : resultUrl.getBytes(StandardCharsets.UTF_8),
                            "text/plain",
                            new ThirdPartyUsage(null, Map.of("resultUrl", resultUrl)),
                            Map.of());
                }
                byte[] image = Base64.getDecoder().decode(base64);
                return new BackgroundRemovalResult(image, "image/png", new ThirdPartyUsage(null, Map.of()), Map.of());
            }
            if (status.equalsIgnoreCase(polling.failedStatus())) {
                throw new PixFlowException(
                        ThirdPartyErrorCode.THIRDPARTY_PROVIDER_ERROR,
                        "job failed",
                        null,
                        Map.of("jobId", jobId),
                        RecoveryHint.RETRY,
                        null,
                        null);
            }
            return null;
        } catch (PixFlowException ex) {
            throw ex;
        } catch (Exception ex) {
            throw errorMapper.invalidResponse("poll parse failed", ex);
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private URI resolve(String path) {
        String base = properties.endpoint();
        if (base == null || base.isBlank()) {
            throw new PixFlowException(
                    ThirdPartyErrorCode.THIRDPARTY_PROVIDER_NOT_CONFIGURED,
                    "provider endpoint is not configured",
                    null,
                    Map.of("providerId", providerId),
                    RecoveryHint.TERMINATE,
                    null,
                    null);
        }
        String suffix = path == null ? "" : path;
        if (base.endsWith("/") && suffix.startsWith("/")) {
            return URI.create(base + suffix.substring(1));
        }
        if (!base.endsWith("/") && !suffix.startsWith("/")) {
            return URI.create(base + "/" + suffix);
        }
        return URI.create(base + suffix);
    }

    private static String encodePathSegment(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }
}
