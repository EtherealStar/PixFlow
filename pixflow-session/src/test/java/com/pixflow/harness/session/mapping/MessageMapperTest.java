package com.pixflow.harness.session.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import com.pixflow.harness.context.model.MessageReference;
import com.pixflow.harness.context.model.MessageRole;
import com.pixflow.harness.session.persistence.MessageEntity;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageMapperTest {

    private final MessageMapper mapper = new MessageMapper(new ObjectMapper());

    @Test
    void toEntityPreservesMarkerMetadataAndTaskIdentity() {
        Message message = new Message(
                "msg-1",
                MessageRole.USER,
                "hello",
                null,
                MessageMetadata.empty()
                        .with(MessageMetadata.COMPACT_BOUNDARY, true)
                        .with(MessageMapper.TASK_ID, "task-1"),
                Instant.parse("2026-06-29T12:00:00Z"));

        MessageEntity entity = mapper.toEntity("conv-1", message, 42);

        assertThat(entity.getId()).isEqualTo("msg-1");
        assertThat(entity.getConversationId()).isEqualTo("conv-1");
        assertThat(entity.getSeq()).isEqualTo(42L);
        assertThat(entity.getRole()).isEqualTo("USER");
        assertThat(entity.getCompactionMarker()).isEqualTo(MessageMapper.MARKER_BOUNDARY);
        assertThat(entity.getTaskId()).isEqualTo("task-1");
        assertThat(entity.getCreatedAt()).isEqualTo(message.createdAt());
    }

    @Test
    void roundTripKeepsMetadataAndSequence() {
        Message original = new Message(
                "msg-2",
                MessageRole.TOOL_RESULT,
                "payload",
                "tool-1",
                MessageMetadata.empty()
                        .with(MessageMetadata.COMPACT_SUMMARY, true)
                        .with(MessageMetadata.TOOL_CALL_IDS, java.util.List.of("tool-1")),
                Instant.parse("2026-06-29T12:01:00Z"));

        MessageEntity entity = mapper.toEntity("conv-1", original, 7);
        Message restored = mapper.toMessage(entity);

        assertThat(restored.id()).isEqualTo("msg-2");
        assertThat(restored.role()).isEqualTo(MessageRole.TOOL_RESULT);
        assertThat(restored.content()).isEqualTo("payload");
        assertThat(restored.toolCallId()).isEqualTo("tool-1");
        assertThat(restored.metadata().flag(MessageMetadata.COMPACT_SUMMARY)).isTrue();
        assertThat(restored.metadata().values().get(MessageMapper.SEQ)).isEqualTo(7L);
    }

    @Test
    void roundTripKeepsOrderedMessageReferencesFromJsonMaps() {
        Message original = Message.user("process", java.util.List.of(
                new MessageReference("package:1", "summer.zip"),
                new MessageReference("package:1/image:2", "summer.zip / front.png")));

        Message restored = mapper.toMessage(mapper.toEntity("conv-1", original, 8));

        assertThat(restored.metadata().references()).containsExactly(
                new MessageReference("package:1", "summer.zip"),
                new MessageReference("package:1/image:2", "summer.zip / front.png"));
    }
}
