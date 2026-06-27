package com.pixflow.infra.thirdparty.bgremoval.provider.removebg;

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
import com.pixflow.infra.thirdparty.http.ThirdPartyHttpRequest;
import com.pixflow.infra.thirdparty.http.ThirdPartyHttpResponse;
import com.pixflow.infra.thirdparty.observability.ThirdPartyMetrics;
import com.pixflow.infra.thirdparty.resilience.ThirdPartyCallContext;
import com.pixflow.infra.thirdparty.resilience.ThirdPartyCallTemplate;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

public final class RemoveBgBackgroundRemovalProvider implements BackgroundRemovalProvider {
    private static final String API = "bg-removal";
    private final String providerId;
    private final ThirdPartyProperties.Provider properties;
    private final ThirdPartyCallTemplate callTemplate;
    private final RestClientThirdPartyHttpInvoker httpInvoker;
    private final ThirdPartyErrorMapper errorMapper;
    private final ThirdPartyMetrics metrics;

    public RemoveBgBackgroundRemovalProvider(
            String providerId,
            ThirdPartyProperties.Provider properties,
            ThirdPartyCallTemplate callTemplate,
            RestClientThirdPartyHttpInvoker httpInvoker,
            ThirdPartyErrorMapper errorMapper,
            ThirdPartyMetrics metrics) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.callTemplate = Objects.requireNonNull(callTemplate, "callTemplate");
        this.httpInvoker = Objects.requireNonNull(httpInvoker, "httpInvoker");
        this.errorMapper = Objects.requireNonNull(errorMapper, "errorMapper");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public BackgroundRemovalResult remove(BackgroundRemovalRequest request) {
        if (request == null) {
            throw new PixFlowException(
                    ThirdPartyErrorCode.THIRDPARTY_INVALID_REQUEST,
                    "request is null",
                    null,
                    Map.of(),
                    RecoveryHint.TERMINATE,
                    null,
                    null);
        }
        return callTemplate.execute(new ThirdPartyCallContext(API, providerId, Duration.ofSeconds(5)), () -> {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer ");
            byte[] body = multipartBody(request);
            ThirdPartyHttpRequest httpRequest = new ThirdPartyHttpRequest(
                    API,
                    providerId,
                    HttpMethod.POST,
                    URI.create(properties.endpoint()),
                    headers,
                    body,
                    MediaType.parseMediaType("multipart/form-data; boundary=" + boundary));
            ThirdPartyHttpResponse response = httpInvoker.exchange(httpRequest);
            if (response.statusCode() >= 400) {
                throw errorMapper.fromStatus(
                        org.springframework.http.HttpStatusCode.valueOf(response.statusCode()),
                        response.headers(),
                        new String(response.body(), StandardCharsets.UTF_8),
                        null);
            }
            metrics.recordResponseBytes(API, providerId, response.body().length);
            return new BackgroundRemovalResult(response.body(), contentType(response), new ThirdPartyUsage(null, Map.of()), Map.of());
        });
    }

    private final String boundary = "pixflow-" + UUID.randomUUID();

    private byte[] multipartBody(BackgroundRemovalRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("--").append(boundary).append("\r\n");
        builder.append("Content-Disposition: form-data; name=\"image_file\"; filename=\"image\"\r\n");
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

    private static String contentType(ThirdPartyHttpResponse response) {
        String contentType = response.headers().getFirst(HttpHeaders.CONTENT_TYPE);
        return contentType == null ? "image/png" : contentType;
    }
}
