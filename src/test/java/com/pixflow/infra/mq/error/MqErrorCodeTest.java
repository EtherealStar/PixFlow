package com.pixflow.infra.mq.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.common.error.ErrorCategory;
import org.junit.jupiter.api.Test;

class MqErrorCodeTest {

    @Test
    void allMqErrorCodesAreDependencyErrors() {
        for (MqErrorCode errorCode : MqErrorCode.values()) {
            assertThat(errorCode.category()).isEqualTo(ErrorCategory.DEPENDENCY);
            assertThat(errorCode.code()).startsWith("MQ_");
        }
    }
}
