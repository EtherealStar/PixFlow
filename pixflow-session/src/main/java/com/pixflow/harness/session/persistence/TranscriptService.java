package com.pixflow.harness.session.persistence;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.harness.context.compaction.CompactionTrigger;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import com.pixflow.harness.context.store.TranscriptPort;
import com.pixflow.harness.session.buffer.TranscriptBuffer;
import com.pixflow.harness.session.chain.ActiveChainResolver;
import com.pixflow.harness.session.config.SessionProperties;
import com.pixflow.harness.session.error.SessionErrorCode;
import com.pixflow.harness.session.externalize.SessionToolResultExternalizer;
import com.pixflow.harness.session.mapping.MessageMapper;
import com.pixflow.harness.session.seq.SequenceAllocator;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

public class TranscriptService implements TranscriptPort {
    private final MessageWriteMapper writeMapper;

    private final MessageReadMapper readMapper;

    private final CompactionMapper compactionMapper;

    private final MessageMapper messageMapper;

    private final SequenceAllocator sequenceAllocator;

    private final TranscriptBuffer buffer;

    private final ActiveChainResolver activeChainResolver;

    private final SessionToolResultExternalizer externalizer;

    private final SessionProperties properties;

    private final MeterRegistry meterRegistry;

    public TranscriptService(
            MessageWriteMapper writeMapper,
            MessageReadMapper readMapper,
            CompactionMapper compactionMapper,
            MessageMapper messageMapper,
            SequenceAllocator sequenceAllocator,
            TranscriptBuffer buffer,
            ActiveChainResolver activeChainResolver,
            SessionToolResultExternalizer externalizer,
            SessionProperties properties,
            MeterRegistry meterRegistry) {
        this.writeMapper = Objects.requireNonNull(writeMapper, "writeMapper");
        this.readMapper = Objects.requireNonNull(readMapper, "readMapper");
        this.compactionMapper = Objects.requireNonNull(compactionMapper, "compactionMapper");
        this.messageMapper = Objects.requireNonNull(messageMapper, "messageMapper");
        this.sequenceAllocator = Objects.requireNonNull(sequenceAllocator, "sequenceAllocator");
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.activeChainResolver = Objects.requireNonNull(activeChainResolver, "activeChainResolver");
        this.externalizer = Objects.requireNonNull(externalizer, "externalizer");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.meterRegistry = meterRegistry;
    }

    @Override
    public List<Message> append(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Message> prepared = messages.stream()
                .map(externalizer::externalizeIfNeeded)
                .toList();
        if (properties.getWriteMode() == SessionProperties.WriteMode.BUFFERED) {
            boolean shouldFlush = buffer.add(conversationId, prepared);
            if (shouldFlush) {
                return flush(conversationId);
            }
            return prepared;
        }
        return persistWithRetry(conversationId, prepared);
    }

    @Override
    public List<Message> load(String conversationId) {
        List<Message> messages = activeChainResolver.resolve(conversationId).stream()
                .map(messageMapper::toMessage)
                .map(externalizer::rehydrate)
                .toList();
        int max = properties.getLoad().getMaxMessages();
        if (max > 0 && messages.size() > max) {
            boolean hasMarkers = messages.size() >= 2
                    && messages.get(0).metadata().flag(MessageMetadata.COMPACT_BOUNDARY)
                    && messages.get(1).metadata().flag(MessageMetadata.COMPACT_SUMMARY);
            if (hasMarkers && max > 2) {
                int tailKeep = max - 2;
                List<Message> tail = messages.subList(Math.max(2, messages.size() - tailKeep), messages.size());
                List<Message> clipped = new ArrayList<>();
                clipped.add(messages.get(0));
                clipped.add(messages.get(1));
                clipped.addAll(tail);
                messages = clipped;
            } else {
                messages = messages.subList(messages.size() - max, messages.size());
            }
        }
        count("pixflow.session.load", "result", "ok");
        return List.copyOf(messages);
    }

    @Override
    @Transactional
    public List<Message> replaceForCompaction(
            String conversationId,
            List<Message> messages,
            CompactionTrigger trigger,
            Map<String, Object> metadata) {
        flush(conversationId);
        if (messages == null || messages.size() < 2) {
            throw new PixFlowException(SessionErrorCode.SESSION_TRANSCRIPT_CORRUPTED,
                    "compaction replacement must contain boundary and summary");
        }
        Message boundary = externalizer.externalizeIfNeeded(messages.get(0));
        Message summary = externalizer.externalizeIfNeeded(messages.get(1));
        if (!boundary.metadata().flag(MessageMetadata.COMPACT_BOUNDARY)
                || !summary.metadata().flag(MessageMetadata.COMPACT_SUMMARY)) {
            throw new PixFlowException(SessionErrorCode.SESSION_TRANSCRIPT_CORRUPTED,
                    "compaction replacement marker metadata is invalid");
        }
        List<Message> markers = persistWithRetry(conversationId, List.of(boundary, summary));
        long covered = coveredUpToSeq(conversationId, messages.subList(2, messages.size()));

        CompactionEntity compaction = new CompactionEntity();
        compaction.setConversationId(conversationId);
        compaction.setBoundaryMessageId(markers.get(0).id());
        compaction.setSummaryMessageId(markers.get(1).id());
        compaction.setCoveredUpToSeq(covered);
        compaction.setTrigger(trigger == null ? CompactionTrigger.AUTO.name() : trigger.name());
        compaction.setMetadata(messageMapper.toJson(metadata == null ? Map.of() : metadata));
        compaction.setCreatedAt(Instant.now());
        compactionMapper.insert(compaction);
        count("pixflow.session.compaction", "trigger", compaction.getTrigger());

        List<Message> result = new ArrayList<>(markers);
        result.addAll(messages.subList(2, messages.size()));
        return List.copyOf(result);
    }

    public List<Message> flush(String conversationId) {
        List<Message> pending = buffer.drain(conversationId);
        if (pending.isEmpty()) {
            return List.of();
        }
        return persistWithRetry(conversationId, pending);
    }

    public void flushAll() {
        buffer.drainAll().forEach(this::persistWithRetry);
    }

    private List<Message> persistWithRetry(String conversationId, List<Message> messages) {
        int attempts = Math.max(1, properties.getSeq().getAllocationRetry());
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                List<Message> sequenced = assignSeq(conversationId, messages);
                List<MessageEntity> entities = sequenced.stream()
                        .map(message -> messageMapper.toEntity(conversationId, message, seq(message)))
                        .toList();
                if (!entities.isEmpty()) {
                    writeMapper.insertIgnoreBatch(entities);
                }
                Map<String, MessageEntity> visible = visibleById(entities);
                if (visible.keySet().containsAll(entities.stream().map(MessageEntity::getId).toList())) {
                    count("pixflow.session.append", "result", "ok");
                    return sequenced.stream()
                            .map(message -> message.withMetadata(message.metadata()
                                    .with(MessageMapper.SEQ, visible.get(message.id()).getSeq())))
                            .toList();
                }
            } catch (DuplicateKeyException ex) {
                last = ex;
            }
            count("pixflow.session.seq.retry");
        }
        throw new PixFlowException(SessionErrorCode.SESSION_SEQ_ALLOCATION_EXHAUSTED,
                "failed to allocate transcript sequence", last);
    }

    private List<Message> assignSeq(String conversationId, List<Message> messages) {
        List<Long> seqs = sequenceAllocator.allocate(conversationId, messages.size());
        List<Message> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            result.add(messages.get(i).withMetadata(messages.get(i).metadata().with(MessageMapper.SEQ, seqs.get(i))));
        }
        return List.copyOf(result);
    }

    private Map<String, MessageEntity> visibleById(List<MessageEntity> entities) {
        if (entities.isEmpty()) {
            return Map.of();
        }
        List<MessageEntity> visible = readMapper.findByIds(entities.stream().map(MessageEntity::getId).toList());
        Map<String, MessageEntity> byId = new LinkedHashMap<>();
        for (MessageEntity entity : visible) {
            byId.put(entity.getId(), entity);
        }
        return byId;
    }

    private long coveredUpToSeq(String conversationId, List<Message> tail) {
        List<String> ids = tail.stream()
                .filter(message -> !message.metadata().flag(MessageMetadata.COMPACT_BOUNDARY))
                .filter(message -> !message.metadata().flag(MessageMetadata.COMPACT_SUMMARY))
                .map(Message::id)
                .toList();
        if (ids.isEmpty()) {
            return readMapper.maxNormalSeq(conversationId);
        }
        Map<String, MessageEntity> byId = new LinkedHashMap<>();
        for (MessageEntity entity : readMapper.findByIds(ids)) {
            if (entity.getCompactionMarker() == null) {
                byId.put(entity.getId(), entity);
            }
        }
        if (byId.size() != ids.size()) {
            throw new PixFlowException(SessionErrorCode.SESSION_TRANSCRIPT_CORRUPTED,
                    "compaction tail references messages that are not persisted");
        }
        long minSeq = byId.values().stream()
                .map(MessageEntity::getSeq)
                .min(Comparator.naturalOrder())
                .orElse(readMapper.maxNormalSeq(conversationId) + 1);
        return Math.max(0, minSeq - 1);
    }

    private static long seq(Message message) {
        Object value = message.metadata().values().get(MessageMapper.SEQ);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private void count(String name, String... tags) {
        if (meterRegistry != null) {
            meterRegistry.counter(name, tags).increment();
        }
    }
}
