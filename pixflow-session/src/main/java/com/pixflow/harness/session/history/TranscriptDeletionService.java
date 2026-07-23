package com.pixflow.harness.session.history;

import com.pixflow.harness.session.buffer.TranscriptBuffer;
import com.pixflow.harness.session.persistence.TranscriptDeletionMapper;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/** Conversation owner 发起真实删除时使用的窄生命周期出口。 */
public class TranscriptDeletionService {
    private final TranscriptDeletionMapper deletionMapper;

    private final TranscriptBuffer buffer;

    public TranscriptDeletionService(
            TranscriptDeletionMapper deletionMapper, TranscriptBuffer buffer) {
        this.deletionMapper = Objects.requireNonNull(deletionMapper, "deletionMapper");
        this.buffer = Objects.requireNonNull(buffer, "buffer");
    }

    @Transactional
    public void deleteConversation(String conversationId) {
        // 先丢弃尚未刷盘的消息，避免删除完成后被延迟写回。
        buffer.drain(conversationId);
        deletionMapper.deleteCompactions(conversationId);
        deletionMapper.deleteMessages(conversationId);
    }
}
