package com.pixflow.module.vision.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.mq.MessageEnvelope;
import com.pixflow.infra.mq.consumer.RetryDecision;
import com.pixflow.module.vision.error.VisionErrorCode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CopyEnrichmentErrorHandlerTest {
    private final CopyEnrichmentErrorHandler handler = new CopyEnrichmentErrorHandler();

    @Test
    void retryForGenericFailure() {
        RetryDecision decision = handler.onError(envelope(), new RuntimeException("temporary"), 0);

        assertThat(decision).isInstanceOf(RetryDecision.Retry.class);
    }

    @Test
    void ackDropForSkipRecovery() {
        PixFlowException ex = new PixFlowException(VisionErrorCode.VISION_IMAGE_TOO_LARGE, "skip")
                .withRecoveryOverride(RecoveryHint.SKIP);

        RetryDecision decision = handler.onError(envelope(), ex, 0);

        assertThat(decision).isInstanceOf(RetryDecision.AckDrop.class);
    }

    @Test
    void deadLetterForTerminateRecovery() {
        PixFlowException ex = new PixFlowException(VisionErrorCode.VISION_EMPTY_REQUEST, "bad");

        RetryDecision decision = handler.onError(envelope(), ex, 0);

        assertThat(decision).isInstanceOf(RetryDecision.DeadLetter.class);
    }

    private MessageEnvelope<CopyEnrichmentMessage> envelope() {
        return MessageEnvelope.current(new CopyEnrichmentMessage(1L), Map.of());
    }
}
