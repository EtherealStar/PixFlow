package com.pixflow.infra.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.ai.config.AiProperties;
import com.pixflow.infra.ai.model.DefaultModelRouter;
import com.pixflow.infra.ai.model.ModelCapability;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.model.TokenUsage;
import com.pixflow.infra.ai.observability.AiMetrics;
import com.pixflow.infra.ai.resilience.ConcurrencyGuard;
import com.pixflow.infra.ai.resilience.ModelRetryRunner;
import com.pixflow.infra.ai.resilience.ModelQuotaGuard;
import com.pixflow.infra.ai.resilience.RetryPolicy;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class DefaultChatModelClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CopyOnWriteArrayList<String> requestBodies = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> requestPaths = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> authorizationHeaders = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void streamsTextDeltasAndCompletedFromServerSentEvents() throws Exception {
        String stream = """
                data: {"choices":[{"delta":{"content":"Hel"},"finish_reason":null}]}

                data: {"choices":[{"delta":{"content":"lo"},"finish_reason":null}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}

                data: [DONE]

                """;
        DefaultChatModelClient client = clientRespondingWith(stream);

        StepVerifier.create(client.stream(userRequest()))
                .expectNextMatches(event -> event instanceof ChatStreamEvent.TextDelta text
                        && text.text().equals("Hel"))
                .expectNextMatches(event -> event instanceof ChatStreamEvent.TextDelta text
                        && text.text().equals("lo"))
                .expectNextMatches(event -> event instanceof ChatStreamEvent.Completed completed
                        && completed.finalText().equals("Hello")
                        && completed.stopReason() == StopReason.STOP
                        && completed.usage().equals(new TokenUsage(1, 2, 3)))
                .verifyComplete();

        JsonNode requestBody = objectMapper.readTree(requestBodies.getFirst());
        assertThat(requestBody.path("stream").asBoolean()).isTrue();
        assertThat(requestBody.path("stream_options").path("include_usage").asBoolean()).isTrue();
    }

    @Test
    void accumulatesStreamingToolCallArgumentsByIndex() throws Exception {
        String stream = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"submit_image_plan","arguments":"{\\\"a\\\""}}]},"finish_reason":null}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":1}"}}]},"finish_reason":null}]}

                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}

                data: [DONE]

                """;
        DefaultChatModelClient client = clientRespondingWith(stream);
        ChatRequest request = new ChatRequest(
                null,
                List.of(new ChatMessage(ChatMessage.Role.USER, List.of(new ChatMessage.TextPart("plan")))),
                List.of(new ToolSchema(
                        "submit_image_plan",
                        "submit plan",
                        "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\"}}}")),
                null,
                null,
                null);

        StepVerifier.create(client.stream(request))
                .expectNextMatches(event -> event instanceof ChatStreamEvent.Completed completed
                        && completed.stopReason() == StopReason.TOOL_CALLS
                        && completed.toolCalls().size() == 1
                        && completed.toolCalls().getFirst().name().equals("submit_image_plan")
                        && completed.toolCalls().getFirst().argumentsJson().equals("{\"a\":1}"))
                .verifyComplete();

        JsonNode requestBody = objectMapper.readTree(requestBodies.getFirst());
        assertThat(requestBody.path("tools").get(0).path("function").path("name").asText())
                .isEqualTo("submit_image_plan");
        assertThat(requestBody.path("tool_choice").asText()).isEqualTo("auto");
    }

    @Test
    void reservesRequestBudgetImmediatelyBeforeTheProviderAttempt() throws Exception {
        String stream = """
                data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}

                """;
        DefaultChatModelClient client = clientRespondingWith(stream);
        AtomicInteger reservations = new AtomicInteger();
        ChatRequest request = new ChatRequest(
                null,
                List.of(new ChatMessage(ChatMessage.Role.USER, List.of(new ChatMessage.TextPart("hello")))),
                null,
                null,
                null,
                reservations::incrementAndGet);

        StepVerifier.create(client.stream(request))
                .expectNextMatches(ChatStreamEvent.Completed.class::isInstance)
                .verifyComplete();

        assertThat(reservations).hasValue(1);
    }

    @Test
    void projectsAssistantToolCallsAndToolResultsToProviderMessages() throws Exception {
        String stream = """
                data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}

                """;
        DefaultChatModelClient client = clientRespondingWith(stream);
        ChatRequest request = new ChatRequest(
                null,
                List.of(
                        new ChatMessage(ChatMessage.Role.USER, List.of(new ChatMessage.TextPart("find it"))),
                        new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(
                                new ChatMessage.ToolCallPart("tc1", "search", "{\"q\":\"x\"}"))),
                        new ChatMessage(ChatMessage.Role.TOOL, List.of(
                                new ChatMessage.ToolResultPart("tc1", "result")))),
                List.of(new ToolSchema("search", "search", "{\"type\":\"object\"}")),
                null,
                null,
                null);

        StepVerifier.create(client.stream(request))
                .expectNextMatches(event -> event instanceof ChatStreamEvent.Completed)
                .verifyComplete();

        JsonNode messages = objectMapper.readTree(requestBodies.getFirst()).path("messages");
        assertThat(messages.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(1).path("tool_calls").get(0).path("id").asText()).isEqualTo("tc1");
        assertThat(messages.get(1).path("tool_calls").get(0).path("function").path("name").asText())
                .isEqualTo("search");
        assertThat(messages.get(2).path("role").asText()).isEqualTo("tool");
        assertThat(messages.get(2).path("tool_call_id").asText()).isEqualTo("tc1");
        assertThat(messages.get(2).path("content").asText()).isEqualTo("result");
    }

    @ParameterizedTest
    @CsvSource({
            "NONE, /v1/chat/completions",
            "/v1, /v1/chat/completions",
            "/proxy/v1/, /proxy/v1/chat/completions",
            "/v1/chat/completions, /v1/chat/completions"
    })
    void completesOnlyMissingOpenAiCompatiblePathSegments(String configuredPath, String expectedPath) throws Exception {
        String stream = """
                data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        String basePath = "NONE".equals(configuredPath) ? "" : configuredPath;
        DefaultChatModelClient client = clientRespondingWith(stream, basePath);

        StepVerifier.create(client.stream(userRequest()))
                .expectNextMatches(ChatStreamEvent.Completed.class::isInstance)
                .verifyComplete();

        assertThat(requestPaths).containsExactly(expectedPath);
        assertThat(authorizationHeaders).containsExactly("Bearer test-key");
    }

    private DefaultChatModelClient clientRespondingWith(String responseBody) throws IOException {
        return clientRespondingWith(responseBody, "");
    }

    private DefaultChatModelClient clientRespondingWith(String responseBody, String basePath) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPaths.add(exchange.getRequestURI().getPath());
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + basePath;
        AiProperties.Roles defaults = AiProperties.Roles.defaults();
        AiProperties.Roles roles = new AiProperties.Roles(
                new AiProperties.RoleConfig("custom", "test-model", ModelCapability.CHAT, 0.3d, 4096, null),
                defaults.vision(),
                defaults.rubricsJudgeText(),
                defaults.rubricsJudgeVision(),
                defaults.imagegen(),
                defaults.embedding(),
                defaults.rerank());
        AiProperties properties = new AiProperties(
                "custom",
                null,
                Map.of("custom", new AiProperties.ProviderConfig("test-key", baseUrl)),
                roles,
                new AiProperties.Retry(1, Duration.ZERO, Duration.ZERO, 0),
                Duration.ofSeconds(5),
                null,
                new AiProperties.QuotaSettings(Map.of(ModelRole.PRIMARY_CHAT, new AiProperties.Quota(
                        "primary-chat", 100, 100, Duration.ofSeconds(1), Duration.ofMinutes(1), 1))));
        var concurrency = new ConcurrencyGuard((role, provider, waitTime) -> () -> { });
        var quota = new ModelQuotaGuard(
                (role, provider, group, cost) -> new com.pixflow.infra.ai.spi.ModelQuotaLimiter.QuotaDecision(
                        true, 99, Duration.ZERO),
                properties,
                new AiMetrics(new SimpleMeterRegistry()));
        return new DefaultChatModelClient(
                properties,
                new DefaultModelRouter(properties),
                new ModelRetryRunner(new RetryPolicy(1, Duration.ZERO, Duration.ZERO, 0)),
                concurrency,
                quota,
                new AiMetrics(new SimpleMeterRegistry()),
                objectMapper,
                WebClient.builder());
    }

    private static ChatRequest userRequest() {
        return new ChatRequest(
                null,
                List.of(new ChatMessage(ChatMessage.Role.USER, List.of(new ChatMessage.TextPart("hello")))),
                null,
                null,
                null,
                null);
    }
}
