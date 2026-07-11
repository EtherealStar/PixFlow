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
import com.pixflow.infra.ai.resilience.ModelQuotaGuard;
import com.pixflow.infra.ai.resilience.ToolCallAccumulator;
import com.pixflow.infra.ai.spi.GlobalConcurrencyLimiter;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * 默认文本模型客户端。
 *
 * <p>当前实现走 OpenAI-compatible chat completions 接口，并通过公共抽象对上隐藏供应商协议。
 */
public final class DefaultChatModelClient implements ChatModelClient {
    private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE =
            new ParameterizedTypeReference<>() {
            };

    private final AiProperties properties;
    private final ModelRouter modelRouter;
    private final ModelRetryRunner retryRunner;
    private final ConcurrencyGuard concurrencyGuard;
    private final ModelQuotaGuard quotaGuard;
    private final AiMetrics metrics;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public DefaultChatModelClient(
            AiProperties properties,
            ModelRouter modelRouter,
            ModelRetryRunner retryRunner,
            ConcurrencyGuard concurrencyGuard,
            ModelQuotaGuard quotaGuard,
            AiMetrics metrics,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter");
        this.retryRunner = Objects.requireNonNull(retryRunner, "retryRunner");
        this.concurrencyGuard = Objects.requireNonNull(concurrencyGuard, "concurrencyGuard");
        this.quotaGuard = Objects.requireNonNull(quotaGuard, "quotaGuard");
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
        return retryRunner.run(request.role(), attempt -> streamOnce(request));
    }

    private Flux<ChatStreamEvent> streamOnce(ChatRequest request) {
        return Flux.defer(() -> {
            ResolvedModel model = modelRouter.resolve(request.role());
            AiProperties.ProviderConfig provider = properties.provider(model.provider());
            if (provider == null) {
                return Flux.error(new PixFlowException(
                        AiErrorCode.MODEL_CONFIGURATION_ERROR,
                        "Chat provider configuration is missing",
                        null,
                        Map.of("provider", model.provider(), "role", model.role().name()),
                        RecoveryHint.TERMINATE,
                        null,
                        null));
            }
            String apiKey = provider.apiKey();
            if (apiKey == null || apiKey.isBlank()) {
                return Flux.error(new PixFlowException(
                        AiErrorCode.MODEL_CONFIGURATION_ERROR,
                        "Chat provider API key is not configured",
                        null,
                        Map.of("provider", model.provider(), "role", model.role().name()),
                        RecoveryHint.TERMINATE,
                        null,
                        null));
            }
            String baseUrl = provider.baseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                return Flux.error(new PixFlowException(
                        AiErrorCode.MODEL_CONFIGURATION_ERROR,
                        "Chat provider base URL is not configured",
                        null,
                        Map.of("provider", model.provider(), "role", model.role().name()),
                        RecoveryHint.TERMINATE,
                        null,
                        null));
            }

            AiMetricsCall call = new AiMetricsCall(metrics, model);
            GlobalConcurrencyLimiter.Permit permit = concurrencyGuard.acquire(
                    request.role(), model.provider(), Duration.ZERO);
            metrics.incrementConcurrency();
            AtomicBoolean recorded = new AtomicBoolean(false);
            try {
                quotaGuard.tryConsume(model);
                return webClient.post()
                        .uri(chatCompletionsUri(baseUrl))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .bodyValue(toProviderStreamRequest(request, model))
                        .exchangeToFlux(this::handleStreamResponse)
                        .timeout(effectiveTimeout(model, request.options()))
                        .doOnNext(event -> {
                            if (event instanceof ChatStreamEvent.Completed completed
                                    && recorded.compareAndSet(false, true)) {
                                call.record(true, completed.usage());
                            }
                        })
                        .doOnError(error -> {
                            if (recorded.compareAndSet(false, true)) {
                                call.record(false, null);
                            }
                        })
                        .doFinally(signalType -> {
                            metrics.decrementConcurrency();
                            permit.close();
                        });
            } catch (RuntimeException ex) {
                metrics.decrementConcurrency();
                permit.close();
                return Flux.error(ex);
            }
        });
    }

    private Flux<ChatStreamEvent> handleStreamResponse(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        if (status.isError()) {
            return response.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .flatMapMany(body -> Flux.error(ProviderErrorMapper.fromHttpStatus(
                            status,
                            null,
                            response.headers().asHttpHeaders(),
                            body.isBlank() ? "model provider returned HTTP " + status.value() : body)));
        }
        StreamState state = new StreamState(objectMapper);
        return response.bodyToFlux(STRING_SSE)
                .concatMap(event -> Flux.fromIterable(state.accept(event.data())))
                .onErrorMap(error -> error instanceof PixFlowException
                        ? error
                        : ProviderErrorMapper.fromMessage(error.getMessage(), error));
    }

    private Map<String, Object> toProviderStreamRequest(ChatRequest request, ResolvedModel model) {
        Map<String, Object> payload = toProviderRequest(request, model);
        payload.put("stream", true);
        payload.put("stream_options", Map.of("include_usage", true));
        return payload;
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
        if (message.role() == ChatMessage.Role.ASSISTANT
                && message.parts().stream().anyMatch(ChatMessage.ToolCallPart.class::isInstance)) {
            value.put("content", assistantTextContent(message.parts()));
            value.put("tool_calls", message.parts().stream()
                    .filter(ChatMessage.ToolCallPart.class::isInstance)
                    .map(ChatMessage.ToolCallPart.class::cast)
                    .map(this::toProviderAssistantToolCall)
                    .toList());
            return value;
        }
        if (message.role() == ChatMessage.Role.TOOL
                && message.parts().size() == 1
                && message.parts().get(0) instanceof ChatMessage.ToolResultPart result) {
            value.put("content", result.content());
            value.put("tool_call_id", result.toolCallId());
            return value;
        }
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

    private String assistantTextContent(List<ChatMessage.Part> parts) {
        String text = parts.stream()
                .filter(ChatMessage.TextPart.class::isInstance)
                .map(ChatMessage.TextPart.class::cast)
                .map(ChatMessage.TextPart::text)
                .collect(java.util.stream.Collectors.joining("\n"));
        return text.isBlank() ? "" : text;
    }

    private Map<String, Object> toProviderAssistantToolCall(ChatMessage.ToolCallPart part) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", part.name());
        function.put("arguments", part.argumentsJson());
        Map<String, Object> toolCall = new LinkedHashMap<>();
        toolCall.put("id", part.id());
        toolCall.put("type", "function");
        toolCall.put("function", function);
        return toolCall;
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

    private static final class StreamState {
        private final ObjectMapper objectMapper;
        private final StringBuilder finalText = new StringBuilder();
        private final ToolCallAccumulator toolCallAccumulator;
        private TokenUsage usage = new TokenUsage(0, 0, 0);
        private boolean completed;

        private StreamState(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            this.toolCallAccumulator = new ToolCallAccumulator(objectMapper);
        }

        private List<ChatStreamEvent> accept(String data) {
            if (completed || data == null || data.isBlank()) {
                return List.of();
            }
            String payload = data.strip();
            if (payload.startsWith("data:")) {
                payload = payload.substring("data:".length()).strip();
            }
            if ("[DONE]".equals(payload)) {
                return complete(StopReason.OTHER);
            }

            JsonNode root;
            try {
                root = objectMapper.readTree(payload);
            } catch (Exception ex) {
                throw ProviderErrorMapper.fromMessage("Invalid streaming model response", ex);
            }
            if (root.has("usage") && !root.path("usage").isNull()) {
                usage = ProviderPayloads.usage(root.path("usage"));
            }
            JsonNode choice = root.path("choices").isArray() && !root.path("choices").isEmpty()
                    ? root.path("choices").get(0)
                    : null;
            if (choice == null) {
                return List.of();
            }

            List<ChatStreamEvent> events = new ArrayList<>();
            JsonNode delta = choice.path("delta");
            String content = delta.path("content").isTextual() ? delta.path("content").asText() : "";
            if (!content.isEmpty()) {
                finalText.append(content);
                events.add(new ChatStreamEvent.TextDelta(content, 0));
            }
            appendToolCalls(delta.path("tool_calls"));

            String finishReason = choice.path("finish_reason").isTextual()
                    ? choice.path("finish_reason").asText()
                    : null;
            if (finishReason != null && !"null".equals(finishReason)) {
                List<ToolCall> toolCalls = toolCallAccumulator.complete();
                events.add(new ChatStreamEvent.Completed(
                        finalText.toString(),
                        toolCalls,
                        stopReason(finishReason, toolCalls),
                        usage));
                completed = true;
            }
            return events;
        }

        private void appendToolCalls(JsonNode toolCalls) {
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                return;
            }
            for (JsonNode item : toolCalls) {
                int index = item.path("index").asInt(0);
                String id = item.path("id").isTextual() ? item.path("id").asText() : null;
                JsonNode function = item.path("function");
                String name = function.path("name").isTextual() ? function.path("name").asText() : null;
                String arguments = function.path("arguments").isTextual() ? function.path("arguments").asText() : null;
                toolCallAccumulator.append(index, id, name, arguments);
            }
        }

        private List<ChatStreamEvent> complete(StopReason fallbackStopReason) {
            List<ToolCall> toolCalls = toolCallAccumulator.complete();
            completed = true;
            return Collections.singletonList(new ChatStreamEvent.Completed(
                    finalText.toString(),
                    toolCalls,
                    toolCalls.isEmpty() ? fallbackStopReason : StopReason.TOOL_CALLS,
                    usage));
        }
    }

    private static URI chatCompletionsUri(String baseUrl) {
        String normalized = baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            return URI.create(normalized);
        }
        if (normalized.endsWith("/v1")) {
            return URI.create(normalized + "/chat/completions");
        }
        return URI.create(normalized + "/v1/chat/completions");
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

        private void record(boolean ok, TokenUsage usage) {
            metrics.recordCall(sample, model.role(), model.provider(), model.capability(), ok);
            if (ok && usage != null) {
                metrics.incrementTokens(model.role(), "prompt", usage.promptTokens());
                metrics.incrementTokens(model.role(), "completion", usage.completionTokens());
            }
        }
    }
}
