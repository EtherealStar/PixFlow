package com.pixflow.infra.thirdparty.bgremoval.provider.aliyunmarket;

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
import com.pixflow.infra.thirdparty.http.ThirdPartyHttpResponse;
import com.pixflow.infra.thirdparty.http.ThirdPartyMutableRequest;
import com.pixflow.infra.thirdparty.observability.ThirdPartyMetrics;
import com.pixflow.infra.thirdparty.resilience.ThirdPartyCallContext;
import com.pixflow.infra.thirdparty.resilience.ThirdPartyCallTemplate;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

public final class AliyunMarketBackgroundRemovalProvider implements BackgroundRemovalProvider {
    private static final String API = "bg-removal";

    private static final String DEFAULT_SUBMIT_PATH = "/api/v1/bg-remove/submit";

    private static final String DEFAULT_QUERY_PATH = "/api/v1/bg-remove/query";

    private static final String DEFAULT_MODEL_TYPE = "general";

    private final String providerId;

    private final ThirdPartyProperties.Provider properties;

    private final ThirdPartyCallTemplate callTemplate;

    private final RestClientThirdPartyHttpInvoker httpInvoker;

    private final ThirdPartyAuthStrategy authStrategy;

    private final ThirdPartyErrorMapper errorMapper;

    private final ThirdPartyMetrics metrics;

    private final ObjectMapper objectMapper;

    public AliyunMarketBackgroundRemovalProvider(
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
        validateRequest(request);
        ThirdPartyProperties.Polling polling = properties.polling();
        Duration timeout = polling == null || polling.timeout() == null ? Duration.ofSeconds(30) : polling.timeout();
        Duration interval = polling == null || polling.interval() == null ? Duration.ofSeconds(1) : polling.interval();
        Instant deadline = Instant.now().plus(timeout);
        return callTemplate.execute(new ThirdPartyCallContext(API, providerId, Duration.ofSeconds(5)), () -> {
            String resultKey = submit(request, polling);
            while (Instant.now().isBefore(deadline)) {
                BackgroundRemovalResult result = query(resultKey, polling);
                if (result != null) {
                    return result;
                }
                sleep(interval, timeout);
            }
            throw new PixFlowException(
                    ThirdPartyErrorCode.THIRDPARTY_PROVIDER_TIMEOUT,
                    "aliyun market query timeout",
                    null,
                    Map.of("resultKey", resultKey),
                    RecoveryHint.RETRY,
                    timeout,
                    null);
        });
    }

    private String submit(BackgroundRemovalRequest request, ThirdPartyProperties.Polling polling) {
        ThirdPartyMutableRequest mutableRequest = new ThirdPartyMutableRequest(API, providerId);
        mutableRequest.method(HttpMethod.POST);
        mutableRequest.uri(resolve(polling == null ? null : polling.submitPath(), DEFAULT_SUBMIT_PATH));
        mutableRequest.contentType(MediaType.parseMediaType("application/json; charset=UTF-8"));
        mutableRequest.headers().set(HttpHeaders.CONTENT_TYPE, "application/json; charset=UTF-8");
        mutableRequest.headers().set("X-Ca-Nonce", UUID.randomUUID().toString());
        mutableRequest.body(writeJson(submitPayload(request)));
        authStrategy.apply(mutableRequest, properties);
        ThirdPartyHttpResponse response = httpInvoker.exchange(mutableRequest.toImmutable());
        if (response.statusCode() >= 400) {
            throw errorMapper.fromStatus(
                    org.springframework.http.HttpStatusCode.valueOf(response.statusCode()),
                    response.headers(),
                    new String(response.body(), StandardCharsets.UTF_8),
                    null);
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            String resultKey = readText(root, "result_key", "resultKey", "data.result_key", "data.resultKey");
            if (resultKey == null || resultKey.isBlank()) {
                throw new IllegalArgumentException("missing result_key");
            }
            return resultKey;
        } catch (Exception ex) {
            throw errorMapper.invalidResponse("submit parse failed", ex);
        }
    }

    private BackgroundRemovalResult query(String resultKey, ThirdPartyProperties.Polling polling) {
        ThirdPartyMutableRequest mutableRequest = new ThirdPartyMutableRequest(API, providerId);
        mutableRequest.method(HttpMethod.POST);
        mutableRequest.uri(resolve(polling == null ? null : polling.statusPath(), DEFAULT_QUERY_PATH));
        mutableRequest.contentType(MediaType.parseMediaType("application/json; charset=UTF-8"));
        mutableRequest.headers().set(HttpHeaders.CONTENT_TYPE, "application/json; charset=UTF-8");
        mutableRequest.headers().set("X-Ca-Nonce", UUID.randomUUID().toString());
        mutableRequest.body(writeJson(queryPayload(resultKey)));
        authStrategy.apply(mutableRequest, properties);
        ThirdPartyHttpResponse response = httpInvoker.exchange(mutableRequest.toImmutable());
        if (response.statusCode() >= 400) {
            throw errorMapper.fromStatus(
                    org.springframework.http.HttpStatusCode.valueOf(response.statusCode()),
                    response.headers(),
                    new String(response.body(), StandardCharsets.UTF_8),
                    null);
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            String status = firstNonBlank(
                    readText(root, "status", "state", "data.status", "data.state"),
                    "");
            if (isSuccess(status, root)) {
                BackgroundRemovalResult result = extractResult(root, resultKey, status);
                metrics.recordResponseBytes(API, providerId, result.image().length);
                return result;
            }
            if (isFailure(status, root)) {
                throw new PixFlowException(
                        ThirdPartyErrorCode.THIRDPARTY_PROVIDER_ERROR,
                        "aliyun market query failed",
                        null,
                        Map.of("resultKey", resultKey, "status", status),
                        RecoveryHint.RETRY,
                        null,
                        null);
            }
            return null;
        } catch (PixFlowException ex) {
            throw ex;
        } catch (Exception ex) {
            throw errorMapper.invalidResponse("query parse failed", ex);
        }
    }

    private BackgroundRemovalResult extractResult(JsonNode root, String resultKey, String status) {
        String resultUrl = readText(
                root,
                "result_url",
                "resultUrl",
                "image_url",
                "imageUrl",
                "data.result_url",
                "data.resultUrl",
                "data.image_url",
                "data.imageUrl");
        if (resultUrl != null && !resultUrl.isBlank()) {
            return new BackgroundRemovalResult(
                    resultUrl.getBytes(StandardCharsets.UTF_8),
                    "text/plain",
                    new ThirdPartyUsage(null, Map.of("resultKey", resultKey, "status", status, "resultUrl", resultUrl)),
                    Map.of("resultKey", resultKey, "status", status, "resultUrl", resultUrl));
        }

        String base64 = readText(
                root,
                "image",
                "result",
                "data.image",
                "data.result",
                "data.image_base64",
                "data.result_base64");
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("missing result image");
        }
        byte[] image = java.util.Base64.getDecoder().decode(base64);
        return new BackgroundRemovalResult(
                image,
                "image/png",
                new ThirdPartyUsage(null, Map.of("resultKey", resultKey, "status", status)),
                Map.of("resultKey", resultKey, "status", status));
    }

    private Map<String, Object> submitPayload(BackgroundRemovalRequest request) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("model_type", modelType());
        task.put("image", sourceUrl(request));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_list", java.util.List.of(task));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data", data);
        return payload;
    }

    private Map<String, Object> queryPayload(String resultKey) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("result_key", resultKey);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data", data);
        return payload;
    }

    private byte[] writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception ex) {
            throw errorMapper.invalidResponse("json encode failed", ex);
        }
    }

    private URI resolve(String path, String fallback) {
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
        String suffix = firstNonBlank(path, fallback);
        if (base.endsWith("/") && suffix.startsWith("/")) {
            return URI.create(base + suffix.substring(1));
        }
        if (!base.endsWith("/") && !suffix.startsWith("/")) {
            return URI.create(base + "/" + suffix);
        }
        return URI.create(base + suffix);
    }

    private String sourceUrl(BackgroundRemovalRequest request) {
        if (request.sourceUri() == null) {
            throw invalidRequest("sourceUri is required");
        }
        URI sourceUri = request.sourceUri();
        if (!sourceUri.isAbsolute()) {
            throw invalidRequest("sourceUri must be absolute");
        }
        String scheme = sourceUri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw invalidRequest("sourceUri scheme must be http or https");
        }
        return sourceUri.normalize().toASCIIString();
    }

    private String modelType() {
        String value = properties.modelType();
        return value == null || value.isBlank() ? DEFAULT_MODEL_TYPE : value.trim();
    }

    private static void validateRequest(BackgroundRemovalRequest request) {
        if (request == null) {
            throw invalidRequest("request is null");
        }
    }

    private static PixFlowException invalidRequest(String message) {
        return new PixFlowException(
                ThirdPartyErrorCode.THIRDPARTY_INVALID_REQUEST,
                message,
                null,
                Map.of(),
                RecoveryHint.TERMINATE,
                null,
                null);
    }

    private void sleep(Duration interval, Duration timeout) {
        try {
            Thread.sleep(Math.max(1L, interval.toMillis()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PixFlowException(
                    ThirdPartyErrorCode.THIRDPARTY_PROVIDER_TIMEOUT,
                    "aliyun market query interrupted",
                    ex,
                    Map.of(),
                    RecoveryHint.RETRY,
                    timeout,
                    null);
        }
    }

    private static boolean isSuccess(String status, JsonNode root) {
        String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return hasResult(root);
        }
        return normalized.contains("success")
                || normalized.contains("succeed")
                || normalized.contains("done")
                || normalized.contains("complete");
    }

    private static boolean isFailure(String status, JsonNode root) {
        String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("fail") || normalized.contains("error") || normalized.contains("cancel");
    }

    private static boolean hasResult(JsonNode root) {
        return readText(root,
                "result_url",
                "resultUrl",
                "image_url",
                "imageUrl",
                "data.result_url",
                "data.resultUrl",
                "data.image_url",
                "data.imageUrl",
                "image",
                "data.image",
                "result",
                "data.result") != null;
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String readText(JsonNode root, String... paths) {
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            JsonNode current = root;
            for (String part : path.split("\\.")) {
                if (current == null) {
                    break;
                }
                current = current.get(part);
            }
            if (current != null && !current.isNull()) {
                String value = current.asText();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }
}
