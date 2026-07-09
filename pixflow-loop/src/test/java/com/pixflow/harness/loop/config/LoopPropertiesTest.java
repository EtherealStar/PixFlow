package com.pixflow.harness.loop.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LoopPropertiesTest {
    @Test
    void toolConcurrencyPoolSizeHasBoundedRange() {
        LoopProperties properties = new LoopProperties();
        properties.setToolConcurrencyPoolSize(1);
        assertThat(properties.toolConcurrencyPoolSize()).isEqualTo(1);
        properties.setToolConcurrencyPoolSize(LoopProperties.MAX_TOOL_CONCURRENCY_POOL_SIZE);
        assertThat(properties.toolConcurrencyPoolSize()).isEqualTo(64);

        assertThatThrownBy(() -> properties.setToolConcurrencyPoolSize(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setToolConcurrencyPoolSize(65))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queueShutdownAndEscalatedOutputHaveBoundedRanges() {
        LoopProperties properties = new LoopProperties();
        properties.setToolQueueCapacity(1);
        assertThat(properties.toolQueueCapacity()).isEqualTo(1);
        properties.setToolQueueCapacity(LoopProperties.MAX_TOOL_QUEUE_CAPACITY);
        assertThat(properties.toolQueueCapacity()).isEqualTo(10_000);
        assertThatThrownBy(() -> properties.setToolQueueCapacity(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setToolQueueCapacity(10_001))
                .isInstanceOf(IllegalArgumentException.class);

        properties.setToolShutdownTimeoutSeconds(1);
        assertThat(properties.toolShutdownTimeoutSeconds()).isEqualTo(1);
        properties.setToolShutdownTimeoutSeconds(300);
        assertThat(properties.toolShutdownTimeoutSeconds()).isEqualTo(300);
        assertThatThrownBy(() -> properties.setToolShutdownTimeoutSeconds(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setToolShutdownTimeoutSeconds(301))
                .isInstanceOf(IllegalArgumentException.class);

        properties.setEscalatedMaxOutputTokens(LoopProperties.MAX_ESCALATED_OUTPUT_TOKENS);
        assertThat(properties.escalatedMaxOutputTokens()).isEqualTo(128_000);
        assertThatThrownBy(() -> properties.setEscalatedMaxOutputTokens(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setEscalatedMaxOutputTokens(128_001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
