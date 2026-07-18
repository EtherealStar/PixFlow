package com.pixflow.harness.session.history;

import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.session.mapping.MessageMapper;
import com.pixflow.harness.session.persistence.MessageEntity;
import com.pixflow.harness.session.persistence.MessageReadMapper;
import java.util.List;
import java.util.Objects;

public final class DefaultTranscriptHistoryReader implements TranscriptHistoryReader {
    private final MessageReadMapper readMapper;

    private final MessageMapper messageMapper;

    public DefaultTranscriptHistoryReader(MessageReadMapper readMapper, MessageMapper messageMapper) {
        this.readMapper = Objects.requireNonNull(readMapper, "readMapper");
        this.messageMapper = Objects.requireNonNull(messageMapper, "messageMapper");
    }

    @Override
    public long count(String conversationId) {
        return readMapper.countMessagesByConversation(conversationId);
    }

    @Override
    public List<TranscriptMessageView> page(String conversationId, long offset, long limit) {
        return readMapper.findMessagesByConversation(conversationId, offset, limit)
                .stream()
                .map(this::toView)
                .toList();
    }

    private TranscriptMessageView toView(MessageEntity entity) {
        Message message = messageMapper.toMessage(entity);
        return new TranscriptMessageView(
                message.id(),
                entity.getSeq() == null ? 0L : entity.getSeq(),
                message.role(),
                message.content(),
                message.toolCallId(),
                message.metadata().references(),
                entity.getCompactionMarker(),
                entity.getTaskId(),
                message.createdAt());
    }
}
