package com.pixflow.module.file.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.consumer.RetryDecision;
import com.pixflow.module.file.error.FileErrorCode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExtractionErrorHandlerTest {
    private final ExtractionErrorHandler handler = new ExtractionErrorHandler();

    @Test
    void terminalPixFlowExceptionGoesToDeadLetter() {
        RetryDecision decision = handler.onError(
                MessageEnvelope.current(new ExtractionMessage(7L), Map.of()),
                new PixFlowException(FileErrorCode.ZIP_PATH_TRAVERSAL, "unsafe zip path"),
                0);

        assertThat(decision).isInstanceOf(RetryDecision.DeadLetter.class);
        assertThat(((RetryDecision.DeadLetter) decision).reason()).contains("unsafe zip path");
    }

    @Test
    void retryableFailureUsesBoundedLinearBackoff() {
        RetryDecision first = handler.onError(
                MessageEnvelope.current(new ExtractionMessage(7L), Map.of()),
                new IllegalStateException("storage unavailable"),
                0);
        RetryDecision capped = handler.onError(
                MessageEnvelope.current(new ExtractionMessage(7L), Map.of()),
                new IllegalStateException("storage unavailable"),
                99);

        assertThat(first).isInstanceOf(RetryDecision.Retry.class);
        assertThat(((RetryDecision.Retry) first).delay()).isEqualTo(Duration.ofSeconds(5));
        assertThat(((RetryDecision.Retry) first).reason()).contains("storage unavailable");
        assertThat(((RetryDecision.Retry) capped).delay()).isEqualTo(Duration.ofSeconds(300));
    }
}
