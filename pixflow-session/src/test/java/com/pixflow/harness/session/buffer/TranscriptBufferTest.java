package com.pixflow.harness.session.buffer;

import com.pixflow.harness.context.model.Message;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptBufferTest {

    @Test
    void addKeepsMessagesHiddenUntilThresholdOrFlush() {
        TranscriptBuffer buffer = new TranscriptBuffer(3, 100);
        List<Message> batch = List.of(Message.user("a"));

        boolean shouldFlush = buffer.add("conv-1", batch);

        assertThat(shouldFlush).isFalse();
        assertThat(buffer.drain("conv-1")).containsExactlyElementsOf(batch);
    }

    @Test
    void addTriggersFlushWhenByteThresholdIsReached() {
        TranscriptBuffer buffer = new TranscriptBuffer(10, 4);

        boolean shouldFlush = buffer.add("conv-1", List.of(Message.user("abcd")));

        assertThat(shouldFlush).isTrue();
        assertThat(buffer.drain("conv-1")).hasSize(1);
    }

    @Test
    void drainAllClearsPendingConversations() {
        TranscriptBuffer buffer = new TranscriptBuffer(10, 100);
        buffer.add("conv-1", List.of(Message.user("a")));
        buffer.add("conv-2", List.of(Message.user("b")));

        assertThat(buffer.drainAll()).hasSize(2);
        assertThat(buffer.drain("conv-1")).isEmpty();
        assertThat(buffer.drain("conv-2")).isEmpty();
    }
}
