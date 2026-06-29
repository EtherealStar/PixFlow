package com.pixflow.harness.session.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.context.compaction.CompactionTrigger;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import com.pixflow.harness.context.model.MessageRole;
import com.pixflow.harness.session.buffer.TranscriptBuffer;
import com.pixflow.harness.session.chain.ActiveChainResolver;
import com.pixflow.harness.session.config.SessionProperties;
import com.pixflow.harness.session.error.SessionErrorCode;
import com.pixflow.harness.session.externalize.SessionToolResultExternalizer;
import com.pixflow.harness.session.mapping.MessageMapper;
import com.pixflow.harness.session.seq.SequenceAllocator;
import com.pixflow.infra.storage.toolresult.StoredToolResultContent;
import com.pixflow.infra.storage.toolresult.StoredToolResultReference;
import com.pixflow.infra.storage.toolresult.ToolResultStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranscriptServiceTest {

    @Test
    void appendLoadAndMultipleCompactionsWorkEndToEndInMemory() {
        SessionFixture fixture = new SessionFixture(SessionProperties.WriteMode.SYNC, 3, 1024, 10, 2);

        fixture.service.append("c1", List.of(
                message("m1", MessageRole.USER, "u1"),
                message("m2", MessageRole.ASSISTANT, "a2"),
                message("m3", MessageRole.USER, "u3")));

        List<Message> first = fixture.service.replaceForCompaction("c1", List.of(
                boundary("b1"),
                summary("s1"),
                message("m3", MessageRole.USER, "u3")), CompactionTrigger.AUTO, Map.of("round", 1));
        assertThat(first).extracting(Message::id).containsExactly("b1", "s1", "m3");
        assertThat(fixture.service.load("c1")).extracting(Message::id).containsExactly("b1", "s1", "m3");

        fixture.service.append("c1", List.of(
                message("m4", MessageRole.USER, "u4"),
                message("m5", MessageRole.USER, "u5")));

        List<Message> second = fixture.service.replaceForCompaction("c1", List.of(
                boundary("b2"),
                summary("s2"),
                message("m4", MessageRole.USER, "u4"),
                message("m5", MessageRole.USER, "u5")), CompactionTrigger.AUTO, Map.of("round", 2));
        assertThat(second).extracting(Message::id).containsExactly("b2", "s2", "m4", "m5");
        assertThat(fixture.service.load("c1")).extracting(Message::id).containsExactly("b2", "s2", "m4", "m5");
    }

    @Test
    void appendIsIdempotentForRepeatedMessageIds() {
        SessionFixture fixture = new SessionFixture(SessionProperties.WriteMode.SYNC, 10, 1024, 10, 2);
        List<Message> batch = List.of(
                message("m1", MessageRole.USER, "u1"),
                message("m2", MessageRole.ASSISTANT, "a2"));

        fixture.service.append("c1", batch);
        fixture.service.append("c1", batch);

        assertThat(fixture.state.messages).hasSize(2);
        assertThat(fixture.service.load("c1")).extracting(Message::id).containsExactly("m1", "m2");
    }

    @Test
    void sequenceAllocationRetriesAfterDuplicateKeyConflict() {
        SessionFixture fixture = new SessionFixture(SessionProperties.WriteMode.SYNC, 10, 1024, 10, 2);
        fixture.writeMapper.failOnce = true;

        List<Message> result = fixture.service.append("c1", List.of(message("m1", MessageRole.USER, "u1")));

        assertThat(result).hasSize(1);
        assertThat(fixture.state.messages).hasSize(1);
        assertThat(fixture.state.messages.get(0).getSeq()).isEqualTo(1L);
    }

    @Test
    void bufferedModeDefersVisibilityUntilFlushAndCompactionFlushesPendingMessages() {
        SessionFixture fixture = new SessionFixture(SessionProperties.WriteMode.BUFFERED, 10, 1024, 10, 2);

        fixture.service.append("c1", List.of(message("m1", MessageRole.USER, "u1")));
        assertThat(fixture.state.messages).isEmpty();

        fixture.service.flush("c1");
        assertThat(fixture.state.messages).hasSize(1);

        fixture.service.append("c1", List.of(message("m2", MessageRole.USER, "u2")));
        fixture.service.replaceForCompaction("c1", List.of(
                boundary("b1"),
                summary("s1"),
                message("m2", MessageRole.USER, "u2")), CompactionTrigger.AUTO, Map.of());

        assertThat(fixture.state.messages)
                .extracting(MessageEntity::getId)
                .contains("m1", "m2", "b1", "s1");
        assertThat(fixture.service.load("c1")).extracting(Message::id).containsExactly("b1", "s1", "m2");
    }

    @Test
    void missingExternalToolResultFallsBackToPreview() {
        SessionFixture fixture = new SessionFixture(SessionProperties.WriteMode.SYNC, 10, 4, 10, 2);

        fixture.service.append("c1", List.of(Message.toolResult("tool-1", "123456789")));
        assertThat(fixture.service.load("c1")).singleElement().satisfies(message -> {
            assertThat(message.content()).isEqualTo("123456789");
            assertThat(message.metadata().flag(SessionToolResultExternalizer.MISSING_EXTERNAL_TOOL_RESULT)).isFalse();
        });

        fixture.toolStorage.clear();

        assertThat(fixture.service.load("c1")).singleElement().satisfies(message -> {
            assertThat(message.content()).isEqualTo("123");
            assertThat(message.metadata().flag(SessionToolResultExternalizer.MISSING_EXTERNAL_TOOL_RESULT)).isTrue();
        });
    }

    @Test
    void replaceForCompactionRejectsCorruptedTailReferences() {
        SessionFixture fixture = new SessionFixture(SessionProperties.WriteMode.SYNC, 10, 1024, 10, 2);

        fixture.service.append("c1", List.of(message("m1", MessageRole.USER, "u1")));

        Message missing = new Message("missing", MessageRole.USER, "x", null, MessageMetadata.empty(), Instant.now());

        assertThatThrownBy(() -> fixture.service.replaceForCompaction("c1", List.of(
                boundary("b1"),
                summary("s1"),
                missing), CompactionTrigger.AUTO, Map.of()))
                .isInstanceOf(PixFlowException.class)
                .extracting("code")
                .extracting("code")
                .isEqualTo(SessionErrorCode.SESSION_TRANSCRIPT_CORRUPTED.code());
    }

    private static Message message(String id, MessageRole role, String content) {
        return new Message(id, role, content, role == MessageRole.TOOL_RESULT ? "tool-1" : null,
                MessageMetadata.empty(), Instant.parse("2026-06-29T12:00:00Z"));
    }

    private static Message boundary(String id) {
        return new Message(id, MessageRole.USER, "boundary", null,
                MessageMetadata.empty().with(MessageMetadata.COMPACT_BOUNDARY, true),
                Instant.parse("2026-06-29T12:00:00Z"));
    }

    private static Message summary(String id) {
        return new Message(id, MessageRole.USER, "summary", null,
                MessageMetadata.empty().with(MessageMetadata.COMPACT_SUMMARY, true),
                Instant.parse("2026-06-29T12:00:00Z"));
    }

    private static final class SessionFixture {
        private final InMemoryState state = new InMemoryState();
        private final InMemoryToolResultStorage toolStorage = new InMemoryToolResultStorage();
        private final InMemoryWriteMapper writeMapper = new InMemoryWriteMapper(state);
        private final InMemoryReadMapper readMapper = new InMemoryReadMapper(state);
        private final InMemoryCompactionMapper compactionMapper = new InMemoryCompactionMapper(state);
        private final SessionToolResultExternalizer externalizer = new SessionToolResultExternalizer(toolStorage, 4, 3);
        private final TranscriptService service;

        private SessionFixture(SessionProperties.WriteMode writeMode, int flushMaxMessages, long flushMaxBytes, int loadMaxMessages, int retry) {
            SessionProperties properties = new SessionProperties();
            properties.setWriteMode(writeMode);
            properties.getBuffer().setFlushMaxMessages(flushMaxMessages);
            properties.getBuffer().setFlushMaxBytes(DataSize.ofBytes(flushMaxBytes));
            properties.getLoad().setMaxMessages(loadMaxMessages);
            properties.getSeq().setAllocationRetry(retry);
            this.service = new TranscriptService(
                    writeMapper,
                    readMapper,
                    compactionMapper,
                    new MessageMapper(new ObjectMapper()),
                    new SequenceAllocator(writeMapper),
                    new TranscriptBuffer(flushMaxMessages, flushMaxBytes),
                    new ActiveChainResolver(readMapper, compactionMapper),
                    externalizer,
                    properties,
                    new SimpleMeterRegistry());
        }
    }

    private static final class InMemoryState {
        private final List<MessageEntity> messages = new ArrayList<>();
        private CompactionEntity latestCompaction;
    }

    private static final class InMemoryWriteMapper implements MessageWriteMapper {
        private final InMemoryState state;
        private boolean failOnce;

        private InMemoryWriteMapper(InMemoryState state) {
            this.state = state;
        }

        @Override
        public long maxSeq(String conversationId) {
            return state.messages.stream()
                    .filter(item -> conversationId.equals(item.getConversationId()))
                    .mapToLong(item -> item.getSeq() == null ? 0L : item.getSeq())
                    .max()
                    .orElse(0L);
        }

        @Override
        public int insertIgnoreBatch(List<MessageEntity> messages) {
            if (failOnce) {
                failOnce = false;
                throw new DuplicateKeyException("simulated duplicate");
            }
            int inserted = 0;
            for (MessageEntity entity : messages) {
                boolean exists = state.messages.stream().anyMatch(item -> item.getId().equals(entity.getId()));
                if (!exists) {
                    state.messages.add(entity);
                    inserted++;
                }
            }
            return inserted;
        }
    }

    private static final class InMemoryReadMapper implements MessageReadMapper {
        private final InMemoryState state;

        private InMemoryReadMapper(InMemoryState state) {
            this.state = state;
        }

        @Override
        public MessageEntity findById(String id) {
            return state.messages.stream().filter(item -> item.getId().equals(id)).findFirst().orElse(null);
        }

        @Override
        public List<MessageEntity> findNormalMessages(String conversationId) {
            return state.messages.stream()
                    .filter(item -> conversationId.equals(item.getConversationId()))
                    .filter(item -> item.getCompactionMarker() == null)
                    .sorted((a, b) -> Long.compare(a.getSeq(), b.getSeq()))
                    .toList();
        }

        @Override
        public List<MessageEntity> findNormalMessagesAfter(String conversationId, long coveredUpToSeq) {
            return state.messages.stream()
                    .filter(item -> conversationId.equals(item.getConversationId()))
                    .filter(item -> item.getCompactionMarker() == null)
                    .filter(item -> item.getSeq() > coveredUpToSeq)
                    .sorted((a, b) -> Long.compare(a.getSeq(), b.getSeq()))
                    .toList();
        }

        @Override
        public List<MessageEntity> findByIds(List<String> ids) {
            return state.messages.stream()
                    .filter(item -> ids.contains(item.getId()))
                    .toList();
        }

        @Override
        public long maxNormalSeq(String conversationId) {
            return state.messages.stream()
                    .filter(item -> conversationId.equals(item.getConversationId()))
                    .filter(item -> item.getCompactionMarker() == null)
                    .mapToLong(item -> item.getSeq() == null ? 0L : item.getSeq())
                    .max()
                    .orElse(0L);
        }
    }

    private static final class InMemoryCompactionMapper implements CompactionMapper {
        private final InMemoryState state;

        private InMemoryCompactionMapper(InMemoryState state) {
            this.state = state;
        }

        @Override
        public CompactionEntity findLatest(String conversationId) {
            return state.latestCompaction;
        }

        @Override
        public int insert(CompactionEntity entity) {
            state.latestCompaction = entity;
            return 1;
        }
    }

    private static final class InMemoryToolResultStorage implements ToolResultStorage {
        private final Map<String, String> objects = new LinkedHashMap<>();

        @Override
        public StoredToolResultReference write(String toolCallId, String content, int previewChars) {
            String id = toolCallId + "-" + content.hashCode();
            String preview = content.substring(0, Math.min(previewChars, content.length()));
            objects.put(id, content);
            return new StoredToolResultReference(id, "bucket", id + ".txt", preview, content.length(), false);
        }

        @Override
        public StoredToolResultContent read(StoredToolResultReference reference) {
            String content = objects.get(reference.id());
            if (content == null) {
                return new StoredToolResultContent(reference.preview(), reference.asMissing());
            }
            return new StoredToolResultContent(content, reference);
        }

        private void clear() {
            objects.clear();
        }
    }
}
