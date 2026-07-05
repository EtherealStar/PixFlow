package com.pixflow.infra.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.ai.config.AiProperties;
import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.infra.ai.error.ProviderErrorMapper;
import com.pixflow.infra.ai.model.ResolvedModel;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * DashScope HTTP 内核：统一处理 base-url、鉴权、HTTP 错误映射和 JSON 读取。
 */
public final class DashScopeHttpClient {
    private static final String PROVIDER_DASHSCOPE = "dashscope";

    private final AiProperties properties;
    private final WebClient webClient;

    public DashScopeHttpClient(AiProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.webClient = Objects.requireNonNull(webClientBuilder, "webClientBuilder").build();
    }

    public Mono<JsonNode> postCompatibleJson(ResolvedModel model, String endpoint, Object payload, Duration timeout) {
        return postJson(model, compatibleUri(endpoint), payload, timeout);
    }

    public Mono<JsonNode> postApiJson(ResolvedModel model, String endpoint, Object payload, Duration timeout) {
        return postJson(model, apiUri(endpoint), payload, timeout);
    }

    private Mono<JsonNode> postJson(ResolvedModel model, URI uri, Object payload, Duration timeout) {
        return Mono.defer(() -> {
            assertDashScope(model);
            String apiKey = apiKey(model);
            return webClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .bodyValue(payload)
                    .exchangeToMono(response -> handleJsonResponse(response, model))
                    .timeout(timeout)
                    .onErrorMap(error -> error instanceof PixFlowException
                            ? error
                            : ProviderErrorMapper.fromMessage(error.getMessage(), error));
        });
    }

    private Mono<JsonNode> handleJsonResponse(ClientResponse response, ResolvedModel model) {
        HttpStatusCode status = response.statusCode();
        if (status.isError()) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMap(body -> Mono.error(ProviderErrorMapper.fromHttpStatus(
                            status,
                            null,
                            response.headers().asHttpHeaders(),
                            body.isBlank() ? "model provider returned HTTP " + status.value() : body)));
        }
        return response.bodyToMono(JsonNode.class)
                .onErrorMap(error -> error instanceof PixFlowException
                        ? error
                        : ProviderErrorMapper.fromMessage(error.getMessage(), error));
    }

    private void assertDashScope(ResolvedModel model) {
        if (!PROVIDER_DASHSCOPE.equals(model.provider())) {
            throw new PixFlowException(
                    AiErrorCode.MODEL_UNSUPPORTED_CAPABILITY,
                    "Unsupported AI provider",
                    null,
                    Map.of("provider", model.provider(), "role", model.role().name()),
                    RecoveryHint.TERMINATE,
                    null,
                    null);
        }
    }

    private String apiKey(ResolvedModel model) {
        String apiKey = properties.dashscope().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new PixFlowException(
                    AiErrorCode.MODEL_CONFIGURATION_ERROR,
                    "DashScope API key is not configured",
                    null,
                    Map.of("provider", model.provider(), "role", model.role().name()),
                    RecoveryHint.TERMINATE,
                    null,
                    null);
        }
        return apiKey;
    }

    private URI compatibleUri(String endpoint) {
        String leaf = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        String base = normalizedBaseUrl();
        if (base.endsWith("/compatible-mode/v1")) {
            return URI.create(base + leaf);
        }
        return URI.create(base + "/compatible-mode/v1" + leaf);
    }

    private URI apiUri(String endpoint) {
        String leaf = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return URI.create(normalizedBaseUrl() + leaf);
    }

    private String normalizedBaseUrl() {
        String base = properties.dashscope().baseUrl();
        if (base == null || base.isBlank()) {
            base = "https://dashscope.aliyuncs.com";
        }
        base = base.strip();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
