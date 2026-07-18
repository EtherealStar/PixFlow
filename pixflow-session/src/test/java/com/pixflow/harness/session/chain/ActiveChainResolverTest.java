package com.pixflow.harness.session.chain;

import com.pixflow.harness.session.persistence.CompactionEntity;
import com.pixflow.harness.session.persistence.CompactionMapper;
import com.pixflow.harness.session.persistence.MessageEntity;
import com.pixflow.harness.session.persistence.MessageReadMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveChainResolverTest {

    @Test
    void resolvesNormalMessagesWhenNoCompactionExists() {
        InMemoryState state = new InMemoryState();
        state.messages.add(message("m1", 1, null));
        state.messages.add(message("m2", 2, null));

        ActiveChainResolver resolver = new ActiveChainResolver(new StubReadMapper(state), new StubCompactionMapper(state));

        assertThat(resolver.resolve("conv-1")).extracting(MessageEntity::getId).containsExactly("m1", "m2");
    }

    @Test
    void resolvesLatestCompactionWithBoundarySummaryAndTail() {
        InMemoryState state = new InMemoryState();
        state.messages.add(message("m1", 1, null));
        state.messages.add(message("m2", 2, null));
        state.messages.add(message("b1", 3, "BOUNDARY"));
        state.messages.add(message("s1", 4, "SUMMARY"));
        state.messages.add(message("m3", 5, null));
        state.messages.add(message("m4", 6, null));
        state.latest = compaction("b1", "s1", 2);

        ActiveChainResolver resolver = new ActiveChainResolver(new StubReadMapper(state), new StubCompactionMapper(state));

        assertThat(resolver.resolve("conv-1")).extracting(MessageEntity::getId)
                .containsExactly("b1", "s1", "m3", "m4");
    }

    @Test
    void fallsBackToNormalMessagesWhenBoundaryOrSummaryMissing() {
        InMemoryState state = new InMemoryState();
        state.messages.add(message("m1", 1, null));
        state.messages.add(message("m2", 2, null));
        state.latest = compaction("missing-b", "missing-s", 1);

        ActiveChainResolver resolver = new ActiveChainResolver(new StubReadMapper(state), new StubCompactionMapper(state));

        assertThat(resolver.resolve("conv-1")).extracting(MessageEntity::getId).containsExactly("m1", "m2");
    }

    private static MessageEntity message(String id, long seq, String marker) {
        MessageEntity entity = new MessageEntity();
        entity.setId(id);
        entity.setConversationId("conv-1");
        entity.setSeq(seq);
        entity.setRole("USER");
        entity.setContent(id);
        entity.setCompactionMarker(marker);
        entity.setCreatedAt(Instant.parse("2026-06-29T12:00:00Z"));
        return entity;
    }

    private static CompactionEntity compaction(String boundary, String summary, long coveredUpToSeq) {
        CompactionEntity entity = new CompactionEntity();
        entity.setBoundaryMessageId(boundary);
        entity.setSummaryMessageId(summary);
        entity.setCoveredUpToSeq(coveredUpToSeq);
        return entity;
    }

    private static final class InMemoryState {
        private final List<MessageEntity> messages = new java.util.ArrayList<>();
        private CompactionEntity latest;
    }

    private static final class StubReadMapper implements MessageReadMapper {
        private final InMemoryState state;

        private StubReadMapper(InMemoryState state) {
            this.state = state;
        }

        @Override
        public MessageEntity findById(String id) {
            return state.messages.stream().filter(item -> item.getId().equals(id)).findFirst().orElse(null);
        }

        @Override
        public List<MessageEntity> findNormalMessages(String conversationId) {
            return state.messages.stream().filter(item -> item.getCompactionMarker() == null).sorted((a, b) -> Long.compare(a.getSeq(), b.getSeq())).toList();
        }

        @Override
        public List<MessageEntity> findNormalMessagesAfter(String conversationId, long coveredUpToSeq) {
            return state.messages.stream()
                    .filter(item -> item.getCompactionMarker() == null)
                    .filter(item -> item.getSeq() > coveredUpToSeq)
                    .sorted((a, b) -> Long.compare(a.getSeq(), b.getSeq()))
                    .toList();
        }

        @Override
        public List<MessageEntity> findByIds(List<String> ids) {
            return state.messages.stream().filter(item -> ids.contains(item.getId())).toList();
        }

        @Override
        public long maxNormalSeq(String conversationId) {
            return state.messages.stream()
                    .filter(item -> item.getCompactionMarker() == null)
                    .mapToLong(MessageEntity::getSeq)
                    .max()
                    .orElse(0L);
        }

        @Override
        public List<MessageEntity> findMessagesByConversation(
                String conversationId,
                long offset,
                long limit) {
            return List.of();
        }

        @Override
        public long countMessagesByConversation(String conversationId) {
            return 0;
        }

    }

    private static final class StubCompactionMapper implements CompactionMapper {
        private final InMemoryState state;

        private StubCompactionMapper(InMemoryState state) {
            this.state = state;
        }

        @Override
        public CompactionEntity findLatest(String conversationId) {
            return state.latest;
        }

        @Override
        public int insert(CompactionEntity entity) {
            state.latest = entity;
            return 1;
        }
    }
}
