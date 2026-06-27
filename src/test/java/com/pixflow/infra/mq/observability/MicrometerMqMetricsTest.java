package com.pixflow.infra.mq.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.mq.PublishFailureType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class MicrometerMqMetricsTest {

    @Test
    void recordsPublishAndConsumeCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMqMetrics metrics = new MicrometerMqMetrics(registry);

        metrics.recordPublishConfirmed("pixflow.test", "test.submit");
        metrics.recordPublishFailed("pixflow.test", "bad.route", PublishFailureType.RETURNED);
        metrics.recordConsumeAck("pixflow.test.q");
        metrics.recordConsumeRetry("pixflow.test.q", 2);
        metrics.recordConsumeDeadLetter("pixflow.test.q");
        metrics.recordConsumeAckDrop("pixflow.test.q");

        assertThat(registry.counter("pixflow.mq.publish", "exchange", "pixflow.test", "routingKey", "test.submit", "result", "confirmed").count())
                .isEqualTo(1);
        assertThat(registry.counter("pixflow.mq.publish", "exchange", "pixflow.test", "routingKey", "bad.route", "result", "failed", "failureType", "RETURNED").count())
                .isEqualTo(1);
        assertThat(registry.counter("pixflow.mq.consume", "queue", "pixflow.test.q", "result", "ack").count())
                .isEqualTo(1);
        assertThat(registry.counter("pixflow.mq.consume", "queue", "pixflow.test.q", "result", "retry").count())
                .isEqualTo(1);
        assertThat(registry.counter("pixflow.mq.consume", "queue", "pixflow.test.q", "result", "dead_letter").count())
                .isEqualTo(1);
        assertThat(registry.counter("pixflow.mq.consume", "queue", "pixflow.test.q", "result", "ack_drop").count())
                .isEqualTo(1);
    }
}
