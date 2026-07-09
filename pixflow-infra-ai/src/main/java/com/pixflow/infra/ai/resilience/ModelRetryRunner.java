package com.pixflow.infra.ai.resilience;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.ai.chat.ChatStreamEvent;
import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.infra.ai.model.ModelRole;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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

    public <T> Mono<T> runBlocking(ModelRole role, Supplier<Mono<T>> attemptSupplier) {
        Objects.requireNonNull(attemptSupplier, "attemptSupplier");
        return Mono.defer(() -> tryBlocking(role, attemptSupplier, 1));
    }

    private <T> Mono<T> tryBlocking(ModelRole role, Supplier<Mono<T>> attemptSupplier, int attempt) {
        return attemptSupplier.get()
                .onErrorResume(error -> {
                    PixFlowException normalized = normalize(role, error);
                    if (!isRetryable(normalized)
                            || normalized.category() == com.pixflow.common.error.ErrorCategory.CONTEXT_LIMIT
                            || attempt >= retryPolicy.maxRetries() + 1) {
                        return Mono.error(normalized);
                    }
                    Duration delay = retryPolicy.delayForAttempt(attempt, normalized.retryAfter());
                    return Mono.defer(() -> tryBlocking(role, attemptSupplier, attempt + 1))
                            .delaySubscription(delay, Schedulers.boundedElastic());
                });
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
                .transform(events -> {
                    AtomicBoolean sawText = new AtomicBoolean(emittedDownstream);
                    return events
                            .doOnNext(event -> {
                                if (event instanceof ChatStreamEvent.TextDelta) {
                                    sawText.set(true);
                                }
                            })
                            .onErrorResume(failure -> {
                                PixFlowException normalized = normalize(role, failure);
                                if (!isRetryable(normalized)
                                        || normalized.category() == com.pixflow.common.error.ErrorCategory.CONTEXT_LIMIT
                                        || attempt >= retryPolicy.maxRetries() + 1) {
                                    return Flux.error(normalized);
                                }
                                Duration delay = retryPolicy.delayForAttempt(attempt, normalized.retryAfter());
                                Flux<ChatStreamEvent> nextAttempt = Flux.defer(() -> tryAttempt(role, attemptSupplier, attempt + 1, false))
                                        .delaySubscription(delay, Schedulers.boundedElastic());
                                if (sawText.get()) {
                                    return Flux.<ChatStreamEvent>just(new ChatStreamEvent.AttemptReset(
                                                    normalized,
                                                    attempt + 1,
                                                    retryPolicy.maxRetries() - attempt))
                                            .concatWith(nextAttempt);
                                }
                                return nextAttempt;
                            });
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
