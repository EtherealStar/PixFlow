package com.pixflow.harness.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.harness.loop.event.AgentEvent;
import com.pixflow.harness.loop.event.AgentEventType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentEventTest {

    @Test
    void assistantCompletedPayloadContainsOnlyMessageId() {
        AgentEvent event = AgentEvent.assistantCompleted("done", "msg-1", Map.of("traceId", "t"));

        assertThat(event.type()).isEqualTo(AgentEventType.ASSISTANT_MESSAGE_COMPLETED);
        assertThat(event.payload()).isEqualTo(Map.of("messageId", "msg-1"));
        assertThat(event.metadata()).containsEntry("traceId", "t");
    }

    @Test
    void assistantCompletedRejectsBlankMessageId() {
        assertThatThrownBy(() -> AgentEvent.assistantCompleted("done", " ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
