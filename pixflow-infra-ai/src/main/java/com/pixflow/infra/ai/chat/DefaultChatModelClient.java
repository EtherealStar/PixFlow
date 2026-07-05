package com.pixflow.infra.ai.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.ai.config.AiProperties;
import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.infra.ai.error.ProviderErrorMapper;
import com.pixflow.infra.ai.model.ChatOptions;
import com.pixflow.infra.ai.model.ModelRouter;
import com.pixflow.infra.ai.model.ResolvedModel;
import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.infra.ai.observability.AiMetrics;
import com.pixflow.infra.ai.provider.ProviderPayloads;
import com.pixflow.infra.ai.resilience.ConcurrencyGuard;
import com.pixflow.infra.ai.resilience.ModelRetryRunner;
import com.pixflow.infra.ai.spi.GlobalConcurrencyLimiter;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 默认文本模型客户端。
 *
 * <p>当前实现走 DashScope OpenAI-compatible 阻塞接口，并通过公共抽象对上隐藏供应商协议。
 * 真流式增量后续可在本类内扩展，不影响上层契约。
 */
public final class DefaultChatModelClient implements ChatModelClient {
    private static final String PROVIDER_DASHSCOPE = "dashscope";

    private final AiProperties properties;
    private final ModelRouter modelRouter;
    private final ModelRetryRunner retryRunner;
    private final ConcurrencyGuard concurrencyGuard;
    private final AiMetrics metrics;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public DefaultChatModelClient(
            AiProperties properties,
            ModelRouter modelRouter,
            ModelRetryRunner retryRunner,
            ConcurrencyGuard concurrencyGuard,
            AiMetrics metrics,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter");
        this.retryRunner = Objects.requireNonNull(retryRunner, "retryRunner");
        this.concurrencyGuard = Objects.requireNonNull(concurrencyGuard, "concurrencyGuard");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.webClient = Objects.requireNonNull(webClientBuilder, "webClientBuilder").build();
    }

    @Override
    public ChatResult call(ChatRequest request) {
        ChatStreamEvent.Completed completed = stream(request)
                .ofType(ChatStreamEvent.Completed.class)
                .blockLast(effectiveTimeout(modelRouter.resolve(request.role()), request.options()).plusSeconds(5));
        if (completed == null) {
            throw new PixFlowException(
                    AiErrorCode.MODEL_PROVIDER_ERROR,
                    "Model call completed without response",
                    null,
                    Map.of("role", request.role().name()),
                    RecoveryHint.RETRY,
                    null,
                    null);
        }
        return new ChatResult(completed.finalText(), completed.toolCalls(), completed.stopReason(), completed.usage());
    }

    @Override
    public Flux<ChatStreamEvent> stream(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        return retryRunner.run(request.role(), attempt -> callOnce(request)
                .map(result -> (ChatStreamEvent) new ChatStreamEvent.Completed(
                        result.finalText(),
                        result.toolCalls(),
                        result.stopReason(),
                        result.usage()))
                .flux());
    }

    private Mono<ChatResult> callOnce(ChatRequest request) {
        return Mono.defer(() -> {
            ResolvedModel model = modelRouter.resolve(request.role());
            if (!PROVIDER_DASHSCOPE.equals(model.provider())) {
                return Mono.error(new PixFlowException(
                        AiErrorCode.MODEL_UNSUPPORTED_CAPABILITY,
                        "Unsupported chat provider",
                        null,
                        Map.of("provider", model.provider(), "role", model.role().name()),
                        RecoveryHint.TERMINATE,
                        null,
                        null));
            }
            String apiKey = properties.dashscope().apiKey();
            if (apiKey == null || apiKey.isBlank()) {
                return Mono.error(new PixFlowException(
                        AiErrorCode.MODEL_CONFIGURATION_ERROR,
                        "DashScope API key is not configured",
                        null,
                        Map.of("provider", model.provider(), "role", model.role().name()),
                        RecoveryHint.TERMINATE,
                        null,
                        null));
            }

            AiMetricsCall call = new AiMetricsCall(metrics, model);
            GlobalConcurrencyLimiter.Permit permit = concurrencyGuard.acquire(request.role(), Duration.ZERO);
            metrics.incrementConcurrency();
            try {
                return webClient.post()
                        .uri(chatCompletionsUri(properties.dashscope().baseUrl()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .bodyValue(toProviderRequest(request, model))
                        .exchangeToMono(response -> handleResponse(response, model))
                        .timeout(effectiveTimeout(model, request.options()))
                        .doOnSuccess(result -> call.record(true, result))
                        .doOnError(error -> call.record(false, null))
                        .doFinally(signalType -> {
                            metrics.decrementConcurrency();
                            permit.close();
                        });
            } catch (RuntimeException ex) {
                metrics.decrementConcurrency();
                permit.close();
                return Mono.error(ex);
            }
        });
    }

    private Mono<ChatResult> handleResponse(ClientResponse response, ResolvedModel model) {
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
                .map(this::fromProviderResponse)
                .onErrorMap(error -> error instanceof PixFlowException
                        ? error
                        : ProviderErrorMapper.fromMessage(error.getMessage(), error));
    }

    private Map<String, Object> toProviderRequest(ChatRequest request, ResolvedModel model) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model.model());
        payload.put("messages", request.messages().stream().map(this::toProviderMessage).toList());
        Double temperature = request.options() != null && request.options().temperature() != null
                ? request.options().temperature()
                : model.temperature();
        Integer maxTokens = request.options() != null && request.options().maxTokens() != null
                ? request.options().maxTokens()
                : model.maxTokens();
        if (temperature != null) {
            payload.put("temperature", temperature);
        }
        if (maxTokens != null) {
            payload.put("max_tokens", maxTokens);
        }
        if (!request.toolSchemas().isEmpty()) {
            payload.put("tools", request.toolSchemas().stream().map(this::toProviderTool).toList());
            payload.put("tool_choice", switch (request.toolChoice()) {
                case AUTO -> "auto";
                case NONE -> "none";
                case REQUIRED -> "required";
            });
        }
        return payload;
    }

    private Map<String, Object> toProviderMessage(ChatMessage message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("role", switch (message.role()) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        });
        if (message.parts().size() == 1 && message.parts().get(0) instanceof ChatMessage.TextPart text) {
            value.put("content", text.text());
            return value;
        }
        List<Map<String, Object>> content = new ArrayList<>();
        for (ChatMessage.Part part : message.parts()) {
            if (part instanceof ChatMessage.TextPart text) {
                content.add(Map.of("type", "text", "text", text.text()));
            } else if (part instanceof ChatMessage.ImagePart image) {
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", ProviderPayloads.imageUrl(image.content()))));
            }
        }
        value.put("content", content);
        return value;
    }

    private Map<String, Object> toProviderTool(ToolSchema schema) {
        JsonNode parameters;
        try {
            parameters = objectMapper.readTree(schema.jsonSchema());
        } catch (Exception ex) {
            throw new PixFlowException(
                    AiErrorCode.INVALID_TOOL_ARGUMENTS,
                    "Invalid tool schema JSON",
                    ex,
                    Map.of("toolName", schema.name()),
                    RecoveryHint.TERMINATE,
                    null,
                    null);
        }
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", schema.name());
        function.put("description", schema.description() == null ? "" : schema.description());
        function.put("parameters", parameters);
        return Map.of("type", "function", "function", function);
    }

    private ChatResult fromProviderResponse(JsonNode root) {
        JsonNode choice = root.path("choices").isArray() && !root.path("choices").isEmpty()
                ? root.path("choices").get(0)
                : null;
        if (choice == null) {
            throw new PixFlowException(
                    AiErrorCode.MODEL_PROVIDER_ERROR,
                    "Model provider response missing choices",
                    null,
                    Map.of(),
                    RecoveryHint.RETRY,
                    null,
                    null);
        }
        JsonNode message = choice.path("message");
        String finalText = message.path("content").isTextual() ? message.path("content").asText() : "";
        List<ToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));
        StopReason stopReason = stopReason(choice.path("finish_reason").asText(null), toolCalls);
        return new ChatResult(finalText, toolCalls, stopReason, ProviderPayloads.usage(root.path("usage")));
    }

    private List<ToolCall> parseToolCalls(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode item : node) {
            JsonNode function = item.path("function");
            String name = function.path("name").asText("");
            String arguments = function.path("arguments").isTextual()
                    ? function.path("arguments").asText()
                    : function.path("arguments").toString();
            try {
                objectMapper.readTree(arguments);
            } catch (Exception ex) {
                throw new PixFlowException(
                        AiErrorCode.INVALID_TOOL_ARGUMENTS,
                        "Invalid tool arguments",
                        ex,
                        Map.of("toolName", name),
                        RecoveryHint.TERMINATE,
                        null,
                        null);
            }
            calls.add(new ToolCall(item.path("id").asText(null), name, arguments));
        }
        return List.copyOf(calls);
    }

    private static StopReason stopReason(String finishReason, List<ToolCall> toolCalls) {
        if (!toolCalls.isEmpty() || "tool_calls".equals(finishReason)) {
            return StopReason.TOOL_CALLS;
        }
        if ("stop".equals(finishReason)) {
            return StopReason.STOP;
        }
        if ("length".equals(finishReason) || "max_tokens".equals(finishReason)) {
            return StopReason.LENGTH;
        }
        if ("content_filter".equals(finishReason)) {
            return StopReason.CONTENT_FILTER;
        }
        return StopReason.OTHER;
    }

    private static URI chatCompletionsUri(String baseUrl) {
        String base = baseUrl == null || baseUrl.isBlank()
                ? "https://dashscope.aliyuncs.com"
                : baseUrl.strip();
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (normalized.endsWith("/chat/completions")) {
            return URI.create(normalized);
        }
        if (normalized.endsWith("/compatible-mode/v1")) {
            return URI.create(normalized + "/chat/completions");
        }
        return URI.create(normalized + "/compatible-mode/v1/chat/completions");
    }

    private static Duration effectiveTimeout(ResolvedModel model, ChatOptions options) {
        if (options != null && options.timeout() != null) {
            return options.timeout();
        }
        return model.timeout();
    }

    private static final class AiMetricsCall {
        private final AiMetrics metrics;
        private final ResolvedModel model;
        private final io.micrometer.core.instrument.Timer.Sample sample;

        private AiMetricsCall(AiMetrics metrics, ResolvedModel model) {
            this.metrics = metrics;
            this.model = model;
            this.sample = metrics.startCall();
        }

        private void record(boolean ok, ChatResult result) {
            metrics.recordCall(sample, model.role(), model.provider(), model.capability(), ok);
            if (ok && result != null) {
                metrics.incrementTokens(model.role(), "prompt", result.usage().promptTokens());
                metrics.incrementTokens(model.role(), "completion", result.usage().completionTokens());
            }
        }
    }
}
