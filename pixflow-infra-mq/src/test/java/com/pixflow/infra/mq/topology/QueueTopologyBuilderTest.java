package com.pixflow.infra.mq.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QueueTopologyBuilderTest {

    @Test
    void buildsDomainNeutralTopology() {
        QueueTopology topology = QueueTopologyBuilder
                .direct("pixflow.test")
                .queue("pixflow.test.q")
                .routingKey("test.submit")
                .deadLetter("pixflow.test.dlx", "pixflow.test.dlq", "test.dead")
                .retry("pixflow.test.retry.q", "test.retry")
                .queueArgument("x-max-length", 1000)
                .build();

        assertThat(topology.exchange()).isEqualTo("pixflow.test");
        assertThat(topology.exchangeType()).isEqualTo("direct");
        assertThat(topology.retryEnabled()).isTrue();
        assertThat(topology.queueArguments()).containsEntry("x-max-length", 1000);
    }

    @Test
    void requiresRetryQueueWhenRetryEnabled() {
        assertThatThrownBy(() -> QueueTopologyBuilder
                .direct("pixflow.test")
                .queue("pixflow.test.q")
                .routingKey("test.submit")
                .deadLetter("pixflow.test.dlx", "pixflow.test.dlq", "test.dead")
                .retry("", "test.retry")
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
