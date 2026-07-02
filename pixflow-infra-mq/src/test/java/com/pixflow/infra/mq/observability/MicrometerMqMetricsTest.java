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

        metrics.recordPublishConfirmed("pixflow-test", "TEST_SUBMIT");
        metrics.recordPublishFailed("pixflow-test", "BAD_ROUTE", PublishFailureType.BROKER_REJECTED);
        metrics.recordConsumeAck("pixflow-test", "pixflow-test-worker");
        metrics.recordConsumeRetry("pixflow-test", "pixflow-test-worker", 2);
        metrics.recordConsumeDeadLetter("pixflow-test", "pixflow-test-worker");
        metrics.recordConsumeAckDrop("pixflow-test", "pixflow-test-worker");

        assertThat(registry.counter("pixflow.mq.publish", "topic", "pixflow-test", "tag", "TEST_SUBMIT", "result", "confirmed").count())
                .isEqualTo(1);
        assertThat(registry.counter("pixflow.mq.publish", "topic", "pixflow-test", "tag", "BAD_ROUTE", "result", "failed", "failureType", "BROKER_REJECTED").count())
                .isEqualTo(1);
        assertThat(registry.counter("pixflow.mq.consume", "topic", "pixflow-test", "consumerGroup", "pixflow-test-worker", "result", "ack").count())
                .isEqualTo(1);
        assertThat(registry.counter("pixflow.mq.consume", "topic", "pixflow-test", "consumerGroup", "pixflow-test-worker", "result", "retry").count())
                .isEqualTo(1);
        assertThat(registry.counter("pixflow.mq.consume", "topic", "pixflow-test", "consumerGroup", "pixflow-test-worker", "result", "dead_letter").count())
                .isEqualTo(1);
        assertThat(registry.counter("pixflow.mq.consume", "topic", "pixflow-test", "consumerGroup", "pixflow-test-worker", "result", "ack_drop").count())
                .isEqualTo(1);
    }
}
