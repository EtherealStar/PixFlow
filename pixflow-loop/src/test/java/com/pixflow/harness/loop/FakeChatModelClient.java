package com.pixflow.harness.loop;

import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.chat.ChatRequest;
import com.pixflow.infra.ai.chat.ChatResult;
import com.pixflow.infra.ai.chat.ChatStreamEvent;
import com.pixflow.infra.ai.chat.StopReason;
import com.pixflow.infra.ai.chat.ToolCall;
import com.pixflow.infra.ai.model.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;

/**
 * 测试用 ChatModelClient：根据调用顺序回放预置的 stream 事件序列。
 *
 * <p>每个调用都用 {@link #next()} 拿出一个 {@link Scenario}（事件序列 + final Result）。
 * 该测试 client 默认不抛任何错误；CONTEXT_LIMIT 等场景可直接覆写
 * {@link #stream(ChatRequest)} 注入。
 */
public class FakeChatModelClient implements ChatModelClient {

    private final Queue<Scenario> queue = new LinkedBlockingQueue<>();
    private final List<ChatRequest> seenRequests = new ArrayList<>();
    private final boolean throwContextLimitOnFirstAttempt;

    public FakeChatModelClient() {
        this(false);
    }

    public FakeChatModelClient(boolean throwContextLimitOnFirstAttempt) {
        this.throwContextLimitOnFirstAttempt = throwContextLimitOnFirstAttempt;
    }

    public FakeChatModelClient enqueue(Scenario scenario) {
        queue.add(Objects.requireNonNull(scenario, "scenario"));
        return this;
    }

    public FakeChatModelClient enqueueText(String finalText) {
        return enqueue(Scenario.ofText(finalText));
    }

    public FakeChatModelClient enqueueToolCalls(List<ToolCall> toolCalls, String partialText) {
        return enqueue(Scenario.ofToolCalls(toolCalls, partialText));
    }

    public int callCount() {
        return seenRequests.size();
    }

    public List<ChatRequest> seenRequests() {
        return List.copyOf(seenRequests);
    }

    @Override
    public ChatResult call(ChatRequest request) {
        seenRequests.add(request);
        Scenario s = queue.poll();
        if (s == null) {
            throw new IllegalStateException("no scenario queued for call");
        }
        return new ChatResult(s.finalText == null ? "" : s.finalText,
                s.toolCalls == null ? List.of() : s.toolCalls,
                s.toolCalls == null || s.toolCalls.isEmpty() ? StopReason.STOP : StopReason.TOOL_CALLS,
                new TokenUsage(10, 5, 15));
    }

    @Override
    public Flux<ChatStreamEvent> stream(ChatRequest request) {
        seenRequests.add(request);
        Scenario s = queue.poll();
        if (s == null) {
            return Flux.error(new IllegalStateException("no scenario queued for stream"));
        }
        return Flux.defer(() -> {
            List<ChatStreamEvent> events = new ArrayList<>();
            if (s.partialText != null) {
                for (int i = 0; i < s.partialText.length(); i += 4) {
                    int end = Math.min(s.partialText.length(), i + 4);
                    events.add(new ChatStreamEvent.TextDelta(s.partialText.substring(i, end), i));
                }
            }
            if (s.toolCalls != null && !s.toolCalls.isEmpty()) {
                events.add(new ChatStreamEvent.Completed(
                        s.finalText == null ? "" : s.finalText,
                        s.toolCalls,
                        StopReason.TOOL_CALLS,
                        new TokenUsage(10, 5, 15)));
            } else {
                events.add(new ChatStreamEvent.Completed(
                        s.partialText == null ? (s.finalText == null ? "" : s.finalText) : s.partialText,
                        List.of(),
                        StopReason.STOP,
                        new TokenUsage(10, 5, 15)));
            }
            return Flux.fromIterable(events);
        });
    }

    public boolean isThrowContextLimitOnFirstAttempt() {
        return throwContextLimitOnFirstAttempt;
    }

    public static final class Scenario {
        final String finalText;
        final String partialText;
        final List<ToolCall> toolCalls;

        private Scenario(String finalText, String partialText, List<ToolCall> toolCalls) {
            this.finalText = finalText;
            this.partialText = partialText;
            this.toolCalls = toolCalls;
        }

        public static Scenario ofText(String finalText) {
            return new Scenario(finalText, finalText, null);
        }

        public static Scenario ofToolCalls(List<ToolCall> toolCalls, String partialText) {
            return new Scenario(null, partialText, toolCalls);
        }
    }

    /** Supplier 形式的便捷方法。 */
    public Supplier<Scenario> nextScenario() {
        return queue::poll;
    }
}
