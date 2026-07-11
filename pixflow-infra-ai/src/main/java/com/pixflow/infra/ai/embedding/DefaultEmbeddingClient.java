package com.pixflow.infra.ai.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.model.ModelRouter;
import com.pixflow.infra.ai.model.ResolvedModel;
import com.pixflow.infra.ai.observability.AiMetrics;
import com.pixflow.infra.ai.provider.DashScopeHttpClient;
import com.pixflow.infra.ai.provider.ProviderPayloads;
import com.pixflow.infra.ai.resilience.ConcurrencyGuard;
import com.pixflow.infra.ai.resilience.ModelRetryRunner;
import com.pixflow.infra.ai.resilience.ModelQuotaGuard;
import com.pixflow.infra.ai.spi.GlobalConcurrencyLimiter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 默认 embedding client。
 */
public final class DefaultEmbeddingClient implements EmbeddingClient {
    private final ModelRouter modelRouter;
    private final ModelRetryRunner retryRunner;
    private final ConcurrencyGuard concurrencyGuard;
    private final ModelQuotaGuard quotaGuard;
    private final AiMetrics metrics;
    private final DashScopeHttpClient httpClient;

    public DefaultEmbeddingClient(
            ModelRouter modelRouter,
            ModelRetryRunner retryRunner,
            ConcurrencyGuard concurrencyGuard,
            ModelQuotaGuard quotaGuard,
            AiMetrics metrics,
            DashScopeHttpClient httpClient) {
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter");
        this.retryRunner = Objects.requireNonNull(retryRunner, "retryRunner");
        this.concurrencyGuard = Objects.requireNonNull(concurrencyGuard, "concurrencyGuard");
        this.quotaGuard = Objects.requireNonNull(quotaGuard, "quotaGuard");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public EmbeddingResult embed(List<String> texts) {
        List<String> normalized = validateTexts(texts);
        ResolvedModel model = modelRouter.resolve(ModelRole.EMBEDDING);
        return retryRunner.runBlocking(ModelRole.EMBEDDING, () -> callOnce(model, normalized))
                .block(model.timeout().plusSeconds(5));
    }

    private reactor.core.publisher.Mono<EmbeddingResult> callOnce(ResolvedModel model, List<String> texts) {
        return reactor.core.publisher.Mono.defer(() -> {
            GlobalConcurrencyLimiter.Permit permit = concurrencyGuard.acquire(
                    ModelRole.EMBEDDING, model.provider(), Duration.ZERO);
            AiMetricsCall call = new AiMetricsCall(metrics, model);
            metrics.incrementConcurrency();
            try {
                quotaGuard.tryConsume(model);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("model", model.model());
                payload.put("input", texts);
                return httpClient.postCompatibleJson(model, "/embeddings", payload, model.timeout())
                        .map(this::parse)
                        .doOnSuccess(result -> call.record(true, result))
                        .doOnError(error -> call.record(false, null))
                        .doFinally(signalType -> {
                            metrics.decrementConcurrency();
                            permit.close();
                        });
            } catch (RuntimeException ex) {
                metrics.decrementConcurrency();
                permit.close();
                return reactor.core.publisher.Mono.error(ex);
            }
        });
    }

    private EmbeddingResult parse(JsonNode root) {
        JsonNode data = root.path("data");
        if (!data.isArray()) {
            throw ProviderPayloads.providerError("Model provider response missing embedding data");
        }
        List<EmbeddingVector> vectors = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                throw ProviderPayloads.providerError("Model provider response missing embedding vector");
            }
            float[] values = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                values[i] = (float) embedding.get(i).asDouble();
            }
            vectors.add(new EmbeddingVector(item.path("index").asInt(vectors.size()), values));
        }
        vectors.sort(Comparator.comparingInt(EmbeddingVector::index));
        return new EmbeddingResult(vectors, ProviderPayloads.usage(root.path("usage")));
    }

    private static List<String> validateTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new PixFlowException(
                    AiErrorCode.MODEL_CONFIGURATION_ERROR,
                    "Embedding input must not be empty",
                    null,
                    Map.of(),
                    RecoveryHint.TERMINATE,
                    null,
                    null);
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new PixFlowException(
                        AiErrorCode.MODEL_CONFIGURATION_ERROR,
                        "Embedding text must not be blank",
                        null,
                        Map.of(),
                        RecoveryHint.TERMINATE,
                        null,
                        null);
            }
        }
        return List.copyOf(texts);
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

        private void record(boolean ok, EmbeddingResult result) {
            metrics.recordCall(sample, model.role(), model.provider(), model.capability(), ok);
            if (ok && result != null) {
                metrics.incrementTokens(model.role(), "prompt", result.usage().promptTokens());
            }
        }
    }
}
