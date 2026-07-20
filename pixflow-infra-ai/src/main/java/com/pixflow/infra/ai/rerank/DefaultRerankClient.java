package com.pixflow.infra.ai.rerank;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 默认候选重排 client。
 */
public final class DefaultRerankClient implements RerankClient {
    private final ModelRouter modelRouter;

    private final ModelRetryRunner retryRunner;

    private final ConcurrencyGuard concurrencyGuard;

    private final ModelQuotaGuard quotaGuard;

    private final AiMetrics metrics;

    private final DashScopeHttpClient httpClient;

    public DefaultRerankClient(
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
    public RerankResult rerank(String query, List<String> candidates) {
        String normalizedQuery = validateQuery(query);
        List<String> normalizedCandidates = validateCandidates(candidates);
        ResolvedModel model = modelRouter.resolve(ModelRole.RERANK);
        return retryRunner.runBlocking(ModelRole.RERANK, () -> callOnce(model, normalizedQuery, normalizedCandidates))
                .block(model.timeout().plusSeconds(5));
    }

    private reactor.core.publisher.Mono<RerankResult> callOnce(
            ResolvedModel model,
            String query,
            List<String> candidates) {
        return reactor.core.publisher.Mono.defer(() -> {
            GlobalConcurrencyLimiter.Permit permit = concurrencyGuard.acquire(
                    ModelRole.RERANK, model.provider(), Duration.ZERO);
            AiMetricsCall call = new AiMetricsCall(metrics, model);
            metrics.incrementConcurrency();
            try {
                quotaGuard.tryConsume(model);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("model", model.model());
                payload.put("query", query);
                payload.put("documents", candidates);
                return httpClient.postApiJson(model,
                        "/api/v1/services/rerank/text-rerank/text-rerank", payload, model.timeout())
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

    private RerankResult parse(JsonNode root) {
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            results = root.path("output").path("results");
        }
        if (!results.isArray()) {
            throw ProviderPayloads.providerError("Model provider response missing rerank results");
        }
        List<RerankScore> scores = new ArrayList<>();
        for (JsonNode item : results) {
            int index = item.has("index")
                    ? item.path("index").asInt()
                    : item.path("document_index").asInt(scores.size());
            double score = item.has("relevance_score")
                    ? item.path("relevance_score").asDouble()
                    : item.path("score").asDouble();
            scores.add(new RerankScore(index, score));
        }
        return new RerankResult(scores, ProviderPayloads.usage(root.path("usage")));
    }

    private static String validateQuery(String query) {
        if (query == null || query.isBlank()) {
            throw invalidInput("Rerank query must not be blank");
        }
        return query;
    }

    private static List<String> validateCandidates(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw invalidInput("Rerank candidates must not be empty");
        }
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                throw invalidInput("Rerank candidate must not be blank");
            }
        }
        return List.copyOf(candidates);
    }

    private static PixFlowException invalidInput(String message) {
        return new PixFlowException(
                AiErrorCode.MODEL_CONFIGURATION_ERROR,
                message,
                null,
                Map.of(),
                RecoveryHint.TERMINATE,
                null,
                null);
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

        private void record(boolean ok, RerankResult result) {
            metrics.recordCall(sample, model.role(), model.provider(), model.capability(), ok);
            if (ok && result != null) {
                metrics.incrementTokens(model.role(), "prompt", result.usage().promptTokens());
            }
        }
    }
}
