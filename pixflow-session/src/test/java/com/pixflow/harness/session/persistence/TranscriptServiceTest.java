package com.pixflow.harness.session.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.context.compaction.CompactionTrigger;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import com.pixflow.harness.context.model.MessageRole;
import com.pixflow.harness.session.buffer.TranscriptBuffer;
import com.pixflow.harness.session.chain.ActiveChainResolver;
import com.pixflow.harness.session.config.SessionProperties;
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

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptServiceTest {

    @Test
    void appendLoadAndCompactionWorkEndToEndInMemory() {
        SessionProperties properties = new SessionProperties();
        properties.getLoad().setMaxMessages(10);
        properties.setWriteMode(SessionProperties.WriteMode.SYNC);

        InMemoryMessageMapper mapper = new InMemoryMessageMapper();
        InMemoryToolResultStorage toolStorage = new InMemoryToolResultStorage();
        SessionToolResultExternalizer externalizer = new SessionToolResultExternalizer(toolStorage, 5, 3);
        TranscriptService service = new TranscriptService(
                new StubWriteMapper(mapper),
                new StubReadMapper(mapper),
                new StubCompactionMapper(mapper),
                new MessageMapper(new ObjectMapper()),
                new SequenceAllocator(new StubWriteMapper(mapper)),
                new TranscriptBuffer(50, 1024),
                new ActiveChainResolver(new StubReadMapper(mapper), new StubCompactionMapper(mapper)),
                externalizer,
                properties,
                new SimpleMeterRegistry());

        List<Message> appended = service.append("c1", List.of(
                Message.user("u1"),
                Message.toolResult("t1", "123456789")));

        assertThat(appended).hasSize(2);
        assertThat(appended.get(1).metadata().flag(com.pixflow.harness.context.model.MessageMetadata.TOOL_RESULT_EXTERNALIZED)).isTrue();
        assertThat(service.load("c1")).hasSize(2);

        List<Message> replacement = List.of(
                new Message(null, MessageRole.USER, "boundary", null,
                        MessageMetadata.empty().with(MessageMetadata.COMPACT_BOUNDARY, true), Instant.now()),
                new Message(null, MessageRole.USER, "summary", null,
                        MessageMetadata.empty().with(MessageMetadata.COMPACT_SUMMARY, true), Instant.now()),
                appended.get(1));
        service.replaceForCompaction("c1", replacement, CompactionTrigger.AUTO, Map.of("reason", "test"));
        assertThat(service.load("c1")).hasSize(3);
    }

    private static final class InMemoryMessageMapper {
        private final List<MessageEntity> messageTable = new ArrayList<>();
        private CompactionEntity latestCompaction;
    }

    private static final class StubWriteMapper implements MessageWriteMapper {
        private final InMemoryMessageMapper state;

        private StubWriteMapper(InMemoryMessageMapper state) {
            this.state = state;
        }

        @Override
        public long maxSeq(String conversationId) {
            return state.messageTable.stream()
                    .filter(message -> conversationId.equals(message.getConversationId()))
                    .mapToLong(entity -> entity.getSeq() == null ? 0L : entity.getSeq())
                    .max()
                    .orElse(0L);
        }

        @Override
        public int insertIgnoreBatch(List<MessageEntity> messages) {
            int inserted = 0;
            for (MessageEntity entity : messages) {
                boolean exists = state.messageTable.stream().anyMatch(item -> item.getId().equals(entity.getId()));
                if (!exists) {
                    state.messageTable.add(entity);
                    inserted++;
                }
            }
            return inserted;
        }
    }

    private static final class StubReadMapper implements MessageReadMapper {
        private final InMemoryMessageMapper state;

        private StubReadMapper(InMemoryMessageMapper state) {
            this.state = state;
        }

        @Override
        public MessageEntity findById(String id) {
            return state.messageTable.stream().filter(item -> item.getId().equals(id)).findFirst().orElse(null);
        }

        @Override
        public List<MessageEntity> findNormalMessages(String conversationId) {
            return state.messageTable.stream()
                    .filter(item -> conversationId.equals(item.getConversationId()))
                    .filter(item -> item.getCompactionMarker() == null)
                    .sorted((a, b) -> Long.compare(a.getSeq(), b.getSeq()))
                    .toList();
        }

        @Override
        public List<MessageEntity> findNormalMessagesAfter(String conversationId, long coveredUpToSeq) {
            return state.messageTable.stream()
                    .filter(item -> conversationId.equals(item.getConversationId()))
                    .filter(item -> item.getCompactionMarker() == null)
                    .filter(item -> item.getSeq() > coveredUpToSeq)
                    .sorted((a, b) -> Long.compare(a.getSeq(), b.getSeq()))
                    .toList();
        }

        @Override
        public List<MessageEntity> findByIds(List<String> ids) {
            return state.messageTable.stream()
                    .filter(item -> ids.contains(item.getId()))
                    .toList();
        }

        @Override
        public long maxNormalSeq(String conversationId) {
            return state.messageTable.stream()
                    .filter(item -> conversationId.equals(item.getConversationId()))
                    .filter(item -> item.getCompactionMarker() == null)
                    .mapToLong(item -> item.getSeq() == null ? 0L : item.getSeq())
                    .max()
                    .orElse(0L);
        }
    }

    private static final class StubCompactionMapper implements CompactionMapper {
        private final InMemoryMessageMapper state;

        private StubCompactionMapper(InMemoryMessageMapper state) {
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
            objects.put(id, content);
            return new StoredToolResultReference(
                    id, "bucket", id + ".txt", content.substring(0, Math.min(previewChars, content.length())), content.length(), false);
        }

        @Override
        public StoredToolResultContent read(StoredToolResultReference reference) {
            String content = objects.get(reference.id());
            if (content == null) {
                return new StoredToolResultContent(reference.preview(), reference.asMissing());
            }
            return new StoredToolResultContent(content, reference);
        }
    }
}
