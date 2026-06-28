package com.pixflow.infra.thirdparty.bgremoval.provider.configurable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.thirdparty.bgremoval.BackgroundRemovalOptions;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriUtils;

public final class ConfigurableHttpBackgroundRemovalProvider implements BackgroundRemovalProvider {
    private static final String API = "bg-removal";
    private static final Pattern SPLIT = Pattern.compile("\\.");

    private final String providerId;
    private final ThirdPartyProperties.Provider properties;
    private final ThirdPartyCallTemplate callTemplate;
    private final RestClientThirdPartyHttpInvoker httpInvoker;
    private final ThirdPartyAuthStrategy authStrategy;
    private final ThirdPartyErrorMapper errorMapper;
    private final ThirdPartyMetrics metrics;
    private final ObjectMapper objectMapper;

    public ConfigurableHttpBackgroundRemovalProvider(
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
        return callTemplate.execute(new ThirdPartyCallContext(API, providerId, Duration.ofSeconds(5)), () -> {
            ThirdPartyHttpRequest httpRequest = buildRequest(request);
            ThirdPartyHttpResponse response = httpInvoker.exchange(httpRequest);
            if (response.statusCode() >= 400) {
                throw errorMapper.fromStatus(
                        org.springframework.http.HttpStatusCode.valueOf(response.statusCode()),
                        response.headers(),
                        new String(response.body(), StandardCharsets.UTF_8),
                        null);
            }
            BackgroundRemovalResult result = extractResult(response);
            metrics.recordResponseBytes(API, providerId, result.image().length);
            return result;
        });
    }

    private ThirdPartyHttpRequest buildRequest(BackgroundRemovalRequest request) {
        if (request == null) {
            throw invalidRequest("request is null", Map.of());
        }
        String mode = properties.request().mode();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        if ("multipart-bytes".equalsIgnoreCase(mode)) {
            requireImage(request, mode);
            String boundary = "pixflow-configurable-" + UUID.randomUUID();
            body = multipartBody(request, firstNonNull(properties.request().fileField(), "image"), boundary);
            headers.setContentType(MediaType.parseMediaType("multipart/form-data; boundary=" + boundary));
        } else if ("json-base64".equalsIgnoreCase(mode)) {
            requireImage(request, mode);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(firstNonNull(properties.request().imageField(), "image"), Base64.getEncoder().encodeToString(request.image()));
            payload.put("contentType", request.contentType());
            payload.put("crop", request.options().crop());
            body = writeJson(payload);
        } else if ("json-url".equalsIgnoreCase(mode)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(firstNonNull(properties.request().urlField(), "url"), safeSourceUrl(request));
            body = writeJson(payload);
        } else {
            throw invalidRequest("unsupported request mode", Map.of("mode", mode));
        }
        ThirdPartyMutableRequest mutableRequest = new ThirdPartyMutableRequest(API, providerId);
        mutableRequest.method(HttpMethod.POST);
        mutableRequest.uri(URI.create(properties.endpoint()));
        mutableRequest.headers().putAll(headers);
        mutableRequest.body(body);
        mutableRequest.contentType(headers.getContentType());
        // Authentication and signing are handled by the HTTP kernel extension point; providers only project requests.
        authStrategy.apply(mutableRequest, properties);
        return mutableRequest.toImmutable();
    }

    private BackgroundRemovalResult extractResult(ThirdPartyHttpResponse response) {
        String mode = properties.response().mode();
        if ("binary-body".equalsIgnoreCase(mode)) {
            return new BackgroundRemovalResult(response.body(), contentType(response), new ThirdPartyUsage(null, Map.of()), Map.of());
        }
        try {
            JsonNode root = objectMapper.readTree(response.body());
            if ("json-base64-field".equalsIgnoreCase(mode)) {
                String field = firstNonNull(properties.response().imageField(), "data.image");
                String value = readText(root, field);
                byte[] image = Base64.getDecoder().decode(value);
                return new BackgroundRemovalResult(image, "image/png", new ThirdPartyUsage(null, Map.of()), Map.of("field", field));
            }
            if ("json-result-url".equalsIgnoreCase(mode)) {
                String field = firstNonNull(properties.response().resultUrlField(), "resultUrl");
                String url = readText(root, field);
                return new BackgroundRemovalResult(url.getBytes(StandardCharsets.UTF_8), "text/plain", new ThirdPartyUsage(null, Map.of()), Map.of("resultUrl", url));
            }
            throw new IllegalArgumentException("unsupported response mode");
        } catch (Exception ex) {
            throw errorMapper.invalidResponse("response parse failed", ex);
        }
    }

    private byte[] writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (Exception ex) {
            throw errorMapper.invalidResponse("json encode failed", ex);
        }
    }

    private static byte[] multipartBody(BackgroundRemovalRequest request, String field, String boundary) {
        StringBuilder builder = new StringBuilder();
        builder.append("--").append(boundary).append("\r\n");
        builder.append("Content-Disposition: form-data; name=\"").append(field).append("\"; filename=\"image\"\r\n");
        builder.append("Content-Type: ").append(request.contentType()).append("\r\n\r\n");
        byte[] head = builder.toString().getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] image = request.image();
        byte[] body = new byte[head.length + image.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(image, 0, body, head.length, image.length);
        System.arraycopy(tail, 0, body, head.length + image.length, tail.length);
        return body;
    }

    private static void requireImage(BackgroundRemovalRequest request, String mode) {
        if (request.image() == null || request.image().length == 0) {
            throw invalidRequest("request image is required", Map.of("mode", mode));
        }
    }

    private static PixFlowException invalidRequest(String message, Map<String, ?> details) {
        return new PixFlowException(
                ThirdPartyErrorCode.THIRDPARTY_INVALID_REQUEST,
                message,
                null,
                details,
                RecoveryHint.TERMINATE,
                null,
                null);
    }

    private static String contentType(ThirdPartyHttpResponse response) {
        String contentType = response.headers().getFirst(HttpHeaders.CONTENT_TYPE);
        return contentType == null ? "image/png" : contentType;
    }

    private static String firstNonNull(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String safeSourceUrl(BackgroundRemovalRequest request) {
        if (request.sourceUri() == null) {
            return null;
        }
        if (!request.sourceUri().isAbsolute()) {
            throw invalidRequest("sourceUri must be absolute", Map.of("sourceUri", "relative"));
        }
        String scheme = request.sourceUri().getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw invalidRequest("sourceUri scheme is not allowed", Map.of("scheme", scheme));
        }
        return request.sourceUri().normalize().toASCIIString();
    }

    private static String readText(JsonNode root, String path) {
        JsonNode current = root;
        for (String part : SPLIT.split(path)) {
            current = current == null ? null : current.get(part);
        }
        if (current == null || current.isNull()) {
            throw new IllegalArgumentException("missing field: " + path);
        }
        return current.asText();
    }
}
