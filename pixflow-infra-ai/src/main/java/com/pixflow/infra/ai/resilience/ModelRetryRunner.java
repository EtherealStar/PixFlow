package com.pixflow.infra.ai.resilience;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.ai.chat.ChatStreamEvent;
import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.infra.ai.model.ModelRole;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 方案 C：以首次下游发射为界的重试 runner。
 */
public final class ModelRetryRunner {
    private final RetryPolicy retryPolicy;

    public ModelRetryRunner(RetryPolicy retryPolicy) {
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    }

    public Flux<ChatStreamEvent> run(ModelRole role, Function<Integer, Flux<ChatStreamEvent>> attemptSupplier) {
        return Flux.defer(() -> tryAttempt(role, attemptSupplier, 1, false));
    }

    private Flux<ChatStreamEvent> tryAttempt(
            ModelRole role,
            Function<Integer, Flux<ChatStreamEvent>> attemptSupplier,
            int attempt,
            boolean emittedDownstream) {
        if (attempt > retryPolicy.maxRetries() + 1) {
            return Flux.error(new PixFlowException(
                    AiErrorCode.MODEL_PROVIDER_ERROR,
                    "Retry exhausted",
                    null,
                    java.util.Map.of("role", role.name(), "attempt", attempt),
                    RecoveryHint.TERMINATE,
                    null,
                    null));
        }
        return attemptSupplier.apply(attempt)
                .materialize()
                .collectList()
                .flatMapMany(signals -> {
                    Throwable failure = null;
                    boolean sawText = false;
                    java.util.List<ChatStreamEvent> events = new java.util.ArrayList<>();
                    for (reactor.core.publisher.Signal<ChatStreamEvent> signal : signals) {
                        if (signal.isOnNext()) {
                            ChatStreamEvent event = signal.get();
                            events.add(event);
                            sawText = sawText || event instanceof ChatStreamEvent.TextDelta;
                        } else if (signal.isOnError()) {
                            failure = signal.getThrowable();
                        }
                    }
                    if (failure == null) {
                        return Flux.fromIterable(events);
                    }
                    PixFlowException normalized = normalize(role, failure);
                    if (!isRetryable(normalized) || normalized.category() == com.pixflow.common.error.ErrorCategory.CONTEXT_LIMIT) {
                        return Flux.error(normalized);
                    }
                    if (attempt >= retryPolicy.maxRetries() + 1) {
                        return Flux.error(normalized);
                    }
                    Duration delay = retryPolicy.delayForAttempt(attempt, normalized.retryAfter());
                    Flux<ChatStreamEvent> reset = sawText
                            ? Flux.just(new ChatStreamEvent.AttemptReset(normalized, attempt + 1, retryPolicy.maxRetries() - attempt))
                            : Flux.empty();
                    return reset.concatWith(Flux.defer(() -> tryAttempt(role, attemptSupplier, attempt + 1, false)).delaySubscription(delay, Schedulers.boundedElastic()));
                });
    }

    private PixFlowException normalize(ModelRole role, Throwable error) {
        if (error instanceof PixFlowException pixFlowException) {
            return pixFlowException;
        }
        return new PixFlowException(
                AiErrorCode.MODEL_PROVIDER_ERROR,
                error == null ? "unknown model error" : error.getMessage(),
                error,
                java.util.Map.of("role", role.name()),
                RecoveryHint.TERMINATE,
                null,
                null);
    }

    private boolean isRetryable(PixFlowException error) {
        return error.category() == com.pixflow.common.error.ErrorCategory.RATE_LIMIT
                || error.category() == com.pixflow.common.error.ErrorCategory.NETWORK
                || error.category() == com.pixflow.common.error.ErrorCategory.PROVIDER;
    }
}
