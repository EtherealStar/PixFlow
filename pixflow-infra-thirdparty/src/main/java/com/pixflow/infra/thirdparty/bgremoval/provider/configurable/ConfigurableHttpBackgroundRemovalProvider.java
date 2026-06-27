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
import com.pixflow.infra.thirdparty.http.ThirdPartyHttpRequest;
import com.pixflow.infra.thirdparty.http.ThirdPartyHttpResponse;
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
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

public final class ConfigurableHttpBackgroundRemovalProvider implements BackgroundRemovalProvider {
    private static final String API = "bg-removal";
    private static final Pattern SPLIT = Pattern.compile("\\.");

    private final String providerId;
    private final ThirdPartyProperties.Provider properties;
    private final ThirdPartyCallTemplate callTemplate;
    private final RestClientThirdPartyHttpInvoker httpInvoker;
    private final ThirdPartyErrorMapper errorMapper;
    private final ThirdPartyMetrics metrics;
    private final ObjectMapper objectMapper;

    public ConfigurableHttpBackgroundRemovalProvider(
            String providerId,
            ThirdPartyProperties.Provider properties,
            ThirdPartyCallTemplate callTemplate,
            RestClientThirdPartyHttpInvoker httpInvoker,
            ThirdPartyErrorMapper errorMapper,
            ThirdPartyMetrics metrics,
            ObjectMapper objectMapper) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.callTemplate = Objects.requireNonNull(callTemplate, "callTemplate");
        this.httpInvoker = Objects.requireNonNull(httpInvoker, "httpInvoker");
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
        String mode = properties.request().mode();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        if ("multipart-bytes".equalsIgnoreCase(mode)) {
            body = request.image();
            headers.setContentType(MediaType.parseMediaType("multipart/form-data"));
        } else if ("json-base64".equalsIgnoreCase(mode)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(firstNonNull(properties.request().imageField(), "image"), Base64.getEncoder().encodeToString(request.image()));
            payload.put("contentType", request.contentType());
            payload.put("crop", request.options().crop());
            body = writeJson(payload);
        } else if ("json-url".equalsIgnoreCase(mode)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(firstNonNull(properties.request().urlField(), "url"), request.sourceUri() == null ? null : request.sourceUri().toString());
            body = writeJson(payload);
        } else {
            throw new PixFlowException(
                    ThirdPartyErrorCode.THIRDPARTY_INVALID_REQUEST,
                    "unsupported request mode",
                    null,
                    Map.of("mode", mode),
                    RecoveryHint.TERMINATE,
                    null,
                    null);
        }
        return new ThirdPartyHttpRequest(API, providerId, HttpMethod.POST, URI.create(properties.endpoint()), headers, body, headers.getContentType());
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

    private static String contentType(ThirdPartyHttpResponse response) {
        String contentType = response.headers().getFirst(HttpHeaders.CONTENT_TYPE);
        return contentType == null ? "image/png" : contentType;
    }

    private static String firstNonNull(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
