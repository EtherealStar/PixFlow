package com.pixflow.harness.loop.stream;

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
 *       {@code TRANSITION(RATE_LIMIT_RETRY)}（仅日志级别，不阻塞）；{@code Completed}
 *       → 累积 finalText / toolCalls / usage + 由 {@code AgentLoop} 统一 emit
 *       {@code ASSISTANT_MESSAGE_COMPLETED}。</li>
 * </ol>
 *
 * <p>失败 attempt 的 partial delta 由 infra/ai 的 {@code ModelRetryRunner} 缓冲式丢弃，
 * 本消费者只看到成功 attempt 的事件。
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
                                RuntimeState state) {
        Objects.requireNonNull(flux, "flux");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(state, "state");

        StringBuilder textBuilder = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        AtomicReference<TokenUsage> lastUsage = new AtomicReference<>(new TokenUsage(0, 0, 0));

        flux.publishOn(Schedulers.boundedElastic())
                .doOnNext(event -> {
                    if (event == null) {
                        return;
                    }
                    if (event instanceof ChatStreamEvent.TextDelta delta) {
                        if (delta.text() != null && !delta.text().isEmpty()) {
                            textBuilder.append(delta.text());
                            sink.emit(AgentEvent.delta(delta.text(), state.metadata()));
                        }
                    } else if (event instanceof ChatStreamEvent.AttemptReset reset) {
                        // 退避在 infra/ai；loop 仅记录
                        state.setTransition(TransitionReason.RATE_LIMIT_RETRY);
                        sink.emit(AgentEvent.transition(
                                TransitionReason.RATE_LIMIT_RETRY,
                                metaOf("attempt", reset.nextAttempt(),
                                        "retriesRemaining", reset.retriesRemaining(),
                                        "traceId", state.traceId())));
                    } else if (event instanceof ChatStreamEvent.Completed completed) {
                        toolCalls.addAll(completed.toolCalls());
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

        TokenUsage usage = lastUsage.get();
        return new ModelOutcome(
                !toolCalls.isEmpty(),
                List.copyOf(toolCalls),
                textBuilder.toString(),
                StopReason.STOP,
                usage,
                false,
                null);
    }

    private static RuntimeException rethrow(Throwable error) {
        if (error instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException(error);
    }

    private static Map<String, Object> metaOf(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
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