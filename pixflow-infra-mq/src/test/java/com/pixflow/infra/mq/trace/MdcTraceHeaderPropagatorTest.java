package com.pixflow.infra.mq.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.mq.retry.RetryHeaders;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcTraceHeaderPropagatorTest {

    private final MdcTraceHeaderPropagator propagator = new MdcTraceHeaderPropagator();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void injectsTraceIdFromMdc() {
        MDC.put("traceId", "trace-1");

        Map<String, Object> headers = propagator.inject(Map.of("business", "value"));

        assertThat(headers)
                .containsEntry("business", "value")
                .containsEntry(RetryHeaders.TRACE_ID, "trace-1");
    }

    @Test
    void restoresAndClosesTraceIdScope() {
        MDC.put("traceId", "old-trace");

        try (TraceScope scope = propagator.restore(Map.of(RetryHeaders.TRACE_ID, "new-trace"))) {
            assertThat(scope.traceId()).isEqualTo("new-trace");
            assertThat(MDC.get("traceId")).isEqualTo("new-trace");
        }

        assertThat(MDC.get("traceId")).isEqualTo("old-trace");
    }
}
