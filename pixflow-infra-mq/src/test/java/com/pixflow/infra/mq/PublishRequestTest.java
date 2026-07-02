package com.pixflow.infra.mq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PublishRequestTest {
    @Test
    void usesRocketMqTopicTagAndKeys() {
        PublishRequest request = PublishRequest.of("pixflow-task", "TASK_EXECUTE", "payload").withKey("task:1");
        assertThat(request.topic()).isEqualTo("pixflow-task");
        assertThat(request.tag()).isEqualTo("TASK_EXECUTE");
        assertThat(request.keys()).containsExactly("task:1");
    }

    @Test
    void rejectsBlankTopic() {
        assertThatThrownBy(() -> PublishRequest.of("", "TAG", "payload")).isInstanceOf(IllegalArgumentException.class);
    }
}
