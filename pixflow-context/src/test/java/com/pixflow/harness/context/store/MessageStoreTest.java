package com.pixflow.harness.context.store;

import com.pixflow.harness.context.compaction.CompactionTrigger;
import com.pixflow.harness.context.model.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageStoreTest {
    @Test
    void currentMessagesReturnsImmutableSnapshot() {
        MessageStore store = new MessageStore();

        store.appendUser("hello");
        List<Message> snapshot = store.currentMessages();

        assertThatThrownBy(() -> snapshot.add(Message.user("mutate")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.currentMessages()).hasSize(1);
    }

    @Test
    void appendWritesThroughTranscriptPortWhenBound() {
        InMemoryTranscript transcript = new InMemoryTranscript();
        MessageStore store = new MessageStore(transcript);
        store.bindConversation("c1");

        store.appendUser("hello");

        assertThat(transcript.appended).hasSize(1);
        assertThat(store.currentMessages()).extracting(Message::content).containsExactly("hello");
    }

    private static final class InMemoryTranscript implements TranscriptPort {
        private final List<Message> appended = new ArrayList<>();

        @Override
        public List<Message> append(String conversationId, List<Message> messages) {
            appended.addAll(messages);
            return List.copyOf(messages);
        }

        @Override
        public List<Message> load(String conversationId) {
            return List.copyOf(appended);
        }

        @Override
        public List<Message> replaceForCompaction(
                String conversationId,
                List<Message> messages,
                CompactionTrigger trigger,
                Map<String, Object> metadata) {
            appended.clear();
            appended.addAll(messages);
            return List.copyOf(messages);
        }
    }
}
