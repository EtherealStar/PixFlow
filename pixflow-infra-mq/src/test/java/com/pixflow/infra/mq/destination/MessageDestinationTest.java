package com.pixflow.infra.mq.destination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MessageDestinationTest {
    @Test
    void buildsTopicTagKeysDestination() {
        MessageDestination destination = MessageDestination.of("pixflow-test", "TEST_SUBMIT").withKey("task:1");
        assertThat(destination.topic()).isEqualTo("pixflow-test");
        assertThat(destination.tag()).isEqualTo("TEST_SUBMIT");
        assertThat(destination.keys()).containsExactly("task:1");
    }

    @Test
    void validatesConsumerBinding() {
        assertThatThrownBy(() -> ConsumerBinding.of("", "TEST", "pixflow-test-worker", String.class))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
