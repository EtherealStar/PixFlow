package com.pixflow.infra.ai.imagegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.pixflow.infra.ai.chat.ChatMessage;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 默认源图重绘 client。
 */
public final class DefaultImageGenClient implements ImageGenClient {
    private final ModelRouter modelRouter;

    private final ModelRetryRunner retryRunner;

    private final ConcurrencyGuard concurrencyGuard;

    private final ModelQuotaGuard quotaGuard;

    private final AiMetrics metrics;

    private final DashScopeHttpClient httpClient;

    public DefaultImageGenClient(
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
    public ImageGenResult generate(ImageGenRequest request) {
        Objects.requireNonNull(request, "request");
        ResolvedModel model = modelRouter.resolve(ModelRole.IMAGEGEN);
        return retryRunner.runBlocking(ModelRole.IMAGEGEN, () -> callOnce(model, request))
                .block(model.timeout().plusSeconds(5));
    }

    private reactor.core.publisher.Mono<ImageGenResult> callOnce(ResolvedModel model, ImageGenRequest request) {
        return reactor.core.publisher.Mono.defer(() -> {
            GlobalConcurrencyLimiter.Permit permit = concurrencyGuard.acquire(
                    ModelRole.IMAGEGEN, model.provider(), Duration.ZERO);
            AiMetricsCall call = new AiMetricsCall(metrics, model);
            metrics.incrementConcurrency();
            try {
                quotaGuard.tryConsume(model);
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("prompt", request.prompt());
                input.put("image", ProviderPayloads.imageUrl(new ChatMessage.BytesImageContent(
                        request.sourceImage(),
                        request.sourceContentType())));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("model", model.model());
                payload.put("input", input);
                return httpClient.postApiJson(model,
                        "/api/v1/services/aigc/image2image/image-synthesis", payload, model.timeout())
                        .map(root -> parse(root, model))
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

    private ImageGenResult parse(JsonNode root, ResolvedModel model) {
        JsonNode imageNode = firstTextNode(
                root.path("image_base64"),
                root.path("image"),
                root.path("output").path("image_base64"),
                root.path("output").path("image"),
                root.path("output").path("images").path(0).path("b64_json"),
                root.path("output").path("images").path(0).path("base64"));
        byte[] image = ProviderPayloads.decodeImageBytes(imageNode.asText(null));
        String contentType = firstTextNode(
                root.path("content_type"),
                root.path("contentType"),
                root.path("output").path("content_type"),
                root.path("output").path("contentType")).asText("image/png");
        return new ImageGenResult(image, contentType, ProviderPayloads.usage(root.path("usage")),
                new ImageProducer(model.provider(), model.model()));
    }

    private static JsonNode firstTextNode(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isTextual() && !node.asText().isBlank()) {
                return node;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
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

        private void record(boolean ok, ImageGenResult result) {
            metrics.recordCall(sample, model.role(), model.provider(), model.capability(), ok);
            if (ok && result != null) {
                metrics.incrementTokens(model.role(), "prompt", result.usage().promptTokens());
                metrics.incrementTokens(model.role(), "completion", result.usage().completionTokens());
            }
        }
    }
}
