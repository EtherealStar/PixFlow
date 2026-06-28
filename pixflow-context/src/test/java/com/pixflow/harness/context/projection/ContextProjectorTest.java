package com.pixflow.harness.context.projection;

import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageRole;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextProjectorTest {
    @Test
    void backsUpWindowStartToKeepToolPair() {
        List<Message> messages = List.of(
                Message.user("old"),
                Message.assistantToolCall("call", "tc1"),
                Message.toolResult("tc1", "result"),
                Message.user("latest"));

        List<Message> projected = new ContextProjector(2).project(messages);

        assertThat(projected).extracting(Message::role)
                .containsExactly(MessageRole.ASSISTANT, MessageRole.TOOL_RESULT, MessageRole.USER);
    }

    @Test
    void dropsOrphanToolResultWhenAssistantCallIsUnavailable() {
        List<Message> messages = List.of(
                Message.toolResult("missing", "orphan"),
                Message.user("latest"));

        List<Message> projected = new ContextProjector(10).project(messages);

        assertThat(projected).extracting(Message::role).containsExactly(MessageRole.USER);
    }

    @Test
    void leavesAttachmentsUntouched() {
        List<Message> messages = List.of(Message.attachment("package:1"), Message.user("process it"));

        List<Message> projected = new ContextProjector(10).project(messages);

        assertThat(projected).extracting(Message::role).containsExactly(MessageRole.ATTACHMENT, MessageRole.USER);
    }
}
