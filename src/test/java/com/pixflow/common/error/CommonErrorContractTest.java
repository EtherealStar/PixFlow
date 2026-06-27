package com.pixflow.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommonErrorContractTest {

    @Test
    void categoryProvidesDefaultRecoveryAndStatus() {
        assertThat(ErrorCategory.RATE_LIMIT.defaultRecovery()).isEqualTo(RecoveryHint.RETRY);
        assertThat(ErrorCategory.RATE_LIMIT.defaultHttpStatus().value()).isEqualTo(429);
    }

    @Test
    void exceptionUsesCategoryDefaultRecoveryUnlessOverridden() {
        PixFlowException exception = new PixFlowException(CommonErrorCode.RATE_LIMITED, "too many", null);
        assertThat(exception.recovery()).isEqualTo(RecoveryHint.RETRY);

        PixFlowException overridden = exception.withRecoveryOverride(RecoveryHint.TERMINATE);
        assertThat(overridden.recovery()).isEqualTo(RecoveryHint.TERMINATE);
    }

    @Test
    void businessExceptionRejectsNonBusinessCode() {
        assertThatThrownBy(() -> new BusinessException(CommonErrorCode.INTERNAL_ERROR, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exceptionCopiesDetailsAndRetryAfter() {
        PixFlowException exception = new PixFlowException(
                CommonErrorCode.INVALID_PARAM,
                "bad",
                null,
                Map.of("field", "name"),
                null,
                Duration.ofSeconds(3),
                "trace-1");

        assertThat(exception.details()).containsEntry("field", "name");
        assertThat(exception.retryAfter()).isEqualTo(Duration.ofSeconds(3));
        assertThat(exception.traceId()).isEqualTo("trace-1");
    }
}
