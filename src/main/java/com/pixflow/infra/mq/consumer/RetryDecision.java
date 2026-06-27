package com.pixflow.infra.mq.consumer;

import java.time.Duration;

public sealed interface RetryDecision permits RetryDecision.Retry, RetryDecision.DeadLetter, RetryDecision.AckDrop {
    record Retry(Duration delay, String reason) implements RetryDecision {
        public Retry {
            delay = delay == null || delay.isNegative() ? Duration.ZERO : delay;
        }
    }

    record DeadLetter(String reason) implements RetryDecision {
    }

    record AckDrop(String reason) implements RetryDecision {
    }
}
