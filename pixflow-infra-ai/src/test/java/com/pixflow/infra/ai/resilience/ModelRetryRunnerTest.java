package com.pixflow.infra.ai.resilience;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.ai.chat.ChatStreamEvent;
import com.pixflow.infra.ai.chat.StopReason;
import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.model.TokenUsage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import org.junit.jupiter.api.Test;

class ModelRetryRunnerTest {

    @Test
    void emitsTextBeforeAttemptFailsThenSendsResetAndRetries() {
        ModelRetryRunner runner = new ModelRetryRunner(new RetryPolicy(1, Duration.ZERO, Duration.ZERO, 0));
        CopyOnWriteArrayList<Integer> attempts = new CopyOnWriteArrayList<>();

        Flux<ChatStreamEvent> events = runner.run(ModelRole.PRIMARY_CHAT, attempt -> {
            attempts.add(attempt);
            if (attempt == 1) {
                return Flux.concat(
                        Flux.just(new ChatStreamEvent.TextDelta("partial", 0)),
                        Flux.error(retryableProviderError()));
            }
            return Flux.just(
                    new ChatStreamEvent.TextDelta("final", 0),
                    new ChatStreamEvent.Completed("final", List.of(), StopReason.STOP, new TokenUsage(0, 0, 0)));
        });

        StepVerifier.create(events)
                .expectNextMatches(event -> event instanceof ChatStreamEvent.TextDelta text
                        && text.text().equals("partial"))
                .expectNextMatches(event -> event instanceof ChatStreamEvent.AttemptReset reset
                        && reset.nextAttempt() == 2
                        && reset.retriesRemaining() == 0)
                .expectNextMatches(event -> event instanceof ChatStreamEvent.TextDelta text
                        && text.text().equals("final"))
                .expectNextMatches(event -> event instanceof ChatStreamEvent.Completed completed
                        && completed.finalText().equals("final"))
                .verifyComplete();

        org.assertj.core.api.Assertions.assertThat(attempts).containsExactly(1, 2);
    }

    @Test
    void retriesTransparentlyBeforeAnyTextIsEmitted() {
        ModelRetryRunner runner = new ModelRetryRunner(new RetryPolicy(1, Duration.ZERO, Duration.ZERO, 0));
        CopyOnWriteArrayList<Integer> attempts = new CopyOnWriteArrayList<>();

        Flux<ChatStreamEvent> events = runner.run(ModelRole.PRIMARY_CHAT, attempt -> {
            attempts.add(attempt);
            if (attempt == 1) {
                return Flux.error(retryableProviderError());
            }
            return Flux.just(new ChatStreamEvent.TextDelta("ok", 0));
        });

        StepVerifier.create(events)
                .expectNextMatches(event -> event instanceof ChatStreamEvent.TextDelta text
                        && text.text().equals("ok"))
                .verifyComplete();

        org.assertj.core.api.Assertions.assertThat(attempts).containsExactly(1, 2);
    }

    private static PixFlowException retryableProviderError() {
        return new PixFlowException(
                AiErrorCode.MODEL_PROVIDER_ERROR,
                "provider failed",
                null,
                Map.of(),
                RecoveryHint.RETRY,
                null,
                null);
    }
}
