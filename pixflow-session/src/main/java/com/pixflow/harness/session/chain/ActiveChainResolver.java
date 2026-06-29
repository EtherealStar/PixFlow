package com.pixflow.harness.session.chain;

import com.pixflow.harness.session.persistence.CompactionEntity;
import com.pixflow.harness.session.persistence.CompactionMapper;
import com.pixflow.harness.session.persistence.MessageEntity;
import com.pixflow.harness.session.persistence.MessageReadMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ActiveChainResolver {
    private final MessageReadMapper messageReadMapper;
    private final CompactionMapper compactionMapper;

    public ActiveChainResolver(MessageReadMapper messageReadMapper, CompactionMapper compactionMapper) {
        this.messageReadMapper = Objects.requireNonNull(messageReadMapper, "messageReadMapper");
        this.compactionMapper = Objects.requireNonNull(compactionMapper, "compactionMapper");
    }

    public List<MessageEntity> resolve(String conversationId) {
        CompactionEntity latest = compactionMapper.findLatest(conversationId);
        if (latest == null) {
            return messageReadMapper.findNormalMessages(conversationId);
        }
        MessageEntity boundary = messageReadMapper.findById(latest.getBoundaryMessageId());
        MessageEntity summary = messageReadMapper.findById(latest.getSummaryMessageId());
        if (boundary == null || summary == null) {
            return messageReadMapper.findNormalMessages(conversationId);
        }
        List<MessageEntity> active = new ArrayList<>();
        active.add(boundary);
        active.add(summary);
        active.addAll(messageReadMapper.findNormalMessagesAfter(conversationId, latest.getCoveredUpToSeq()));
        return List.copyOf(active);
    }
}
