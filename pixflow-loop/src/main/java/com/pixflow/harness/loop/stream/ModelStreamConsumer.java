package com.pixflow.harness.loop.stream;

import com.pixflow.common.concurrent.CancellationToken;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.harness.loop.RuntimeState;
import com.pixflow.harness.loop.TransitionReason;
import com.pixflow.harness.loop.event.AgentEvent;
import com.pixflow.harness.loop.event.AgentEventSink;
import com.pixflow.infra.ai.chat.ChatStreamEvent;
import com.pixflow.infra.ai.chat.StopReason;
import com.pixflow.infra.ai.chat.ToolCall;
import com.pixflow.infra.ai.model.TokenUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 阻塞式消费 {@code Flux<ChatStreamEvent>}，同步 emit AgentEvent 到 sink。
 *
 * <p>执行模型：
 * <ol>
 *   <li>在调用线程立即订阅（订阅动作轻量）。</li>
 *   <li>立即用 {@link Schedulers#boundedElastic()} 把 onNext 桥接到回合线程，
 *       保证 {@code sink.emit} 与 {@code onCompleted} 在同一线程串行执行
 *       （与 {@code harness/loop.md} §七的「阻塞主循环 + 事件回调」口径一致）。</li>
 *   <li>{@code TextDelta} → {@code ASSISTANT_DELTA}；{@code AttemptReset} →
 *       {@code TRANSITION(RATE_LIMIT_RETRY)}（非终态重试提示）；{@code Completed}
 *       → 累积 finalText / toolCalls / usage + 由 {@code AgentLoop} 统一 emit
 *       {@code ASSISTANT_MESSAGE_COMPLETED}。</li>
 * </ol>
 *
 * <p>模型 retry 只归 infra/ai 所有；本消费者只把 {@code AttemptReset} 投影成 loop
 * transition，不能在这里重新订阅模型流。
 */
public final class ModelStreamConsumer {

    /** 单 attempt 调用的订阅超时（防御用，正常情况 model 流远小于此）。 */
    private static final Duration SUBSCRIBE_TIMEOUT = Duration.ofMinutes(5);

    public ModelStreamConsumer() {
    }

    /**
     * 消费一次 stream 调用；该方法为阻塞调用，订阅完成后立即把流桥接到 boundedElastic
     * 上的回合线程同步消费，{@code onCompleted} 也在同一线程触发。
     */
    public ModelOutcome consume(Flux<ChatStreamEvent> flux,
                                AgentEventSink sink,
                                RuntimeState state,
                                Map<String, Object> eventMetadata,
                                CancellationToken cancellation) {
        Objects.requireNonNull(flux, "flux");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(cancellation, "cancellation");
        cancellation.throwIfCancellationRequested();

        Map<String, Object> baseEventMetadata = eventMetadata == null ? Map.of() : Map.copyOf(eventMetadata);
        StringBuilder textBuilder = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        AtomicReference<TokenUsage> lastUsage = new AtomicReference<>(new TokenUsage(0, 0, 0));
        AtomicReference<StopReason> stopReason = new AtomicReference<>(StopReason.STOP);

        // 模型流先结束时 Reactor 只取消自己的订阅，不能反向 cancel 公共 token signal。
        Flux<ChatStreamEvent> cancellable = flux
                .publishOn(Schedulers.boundedElastic())
                .takeUntilOther(Mono.fromFuture(
                        cancellation.cancellationSignal().toCompletableFuture(),
                        true));
        try {
            cancellable
                .doOnNext(event -> {
                    cancellation.throwIfCancellationRequested();
                    if (event == null) {
                        return;
                    }
                    if (event instanceof ChatStreamEvent.TextDelta delta) {
                        if (delta.text() != null && !delta.text().isEmpty()) {
                            textBuilder.append(delta.text());
                            sink.emit(AgentEvent.delta(delta.text(), baseEventMetadata));
                        }
                    } else if (event instanceof ChatStreamEvent.AttemptReset reset) {
                        // 退避在 infra/ai；loop 只发非终态 transition 让前端 timeline 可见。
                        state.setTransition(TransitionReason.RATE_LIMIT_RETRY);
                        sink.emit(AgentEvent.transition(
                                TransitionReason.RATE_LIMIT_RETRY,
                                retryMetadata(baseEventMetadata, reset, state.traceId())));
                    } else if (event instanceof ChatStreamEvent.Completed completed) {
                        toolCalls.addAll(completed.toolCalls());
                        if (completed.stopReason() != null) {
                            stopReason.set(completed.stopReason());
                        }
                        if (completed.usage() != null) {
                            lastUsage.set(completed.usage());
                        }
                        if (textBuilder.length() == 0 && completed.finalText() != null) {
                            textBuilder.append(completed.finalText());
                        }
                    }
                })
                .doOnError(error -> { throw rethrow(error); })
                .blockLast(SUBSCRIBE_TIMEOUT);
        } catch (RuntimeException error) {
            cancellation.throwIfCancellationRequested();
            throw error;
        }
        cancellation.throwIfCancellationRequested();

        TokenUsage usage = lastUsage.get();
        StopReason finalStopReason = stopReason.get();
        return new ModelOutcome(
                !toolCalls.isEmpty(),
                List.copyOf(toolCalls),
                textBuilder.toString(),
                finalStopReason,
                usage,
                finalStopReason == StopReason.LENGTH,
                null);
    }

    private static RuntimeException rethrow(Throwable error) {
        if (error instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException(error);
    }

    private static Map<String, Object> metaOf(Map<String, Object> base, Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (base != null) {
            map.putAll(base);
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> retryMetadata(Map<String, Object> base,
                                                     ChatStreamEvent.AttemptReset reset,
                                                     String fallbackTraceId) {
        PixFlowException error = reset.error();
        String errorCode = error == null || error.code() == null
                ? "MODEL_PROVIDER_ERROR"
                : error.code().code();
        String message = Sanitizer.sanitizeMessage(error == null ? "" : error.getMessage());
        String traceId = error != null && error.traceId() != null ? error.traceId() : fallbackTraceId;
        Map<String, Object> metadata = metaOf(base,
                "attempt", reset.nextAttempt(),
                "retriesRemaining", reset.retriesRemaining(),
                "errorCode", errorCode,
                "message", message == null || message.isBlank() ? "model stream interrupted, retrying" : message,
                "retrying", true);
        if (traceId != null && !traceId.isBlank()) {
            metadata.put("traceId", traceId);
        }
        return metadata;
    }

    /**
     * 单次模型调用的最终结果。
     */
    public record ModelOutcome(
            boolean hasToolCalls,
            List<ToolCall> toolCalls,
            String finalText,
            StopReason stopReason,
            TokenUsage usage,
            boolean outputInterrupted,
            String systemPromptFingerprint) {
    }
}
