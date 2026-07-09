package com.pixflow.agent.sessionmemory;

import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Session Memory 仓库门面。
 */
@Repository
public class SessionMemoryRepository {

    private final SessionMemoryMapper mapper;

    public SessionMemoryRepository(SessionMemoryMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<SessionMemory> findByConversationId(String conversationId) {
        return mapper.findByConversationId(conversationId);
    }

    public SaveResult saveIfAdvances(SessionMemory memory) {
        Optional<SessionMemory> before = findByConversationId(memory.getConversationId());
        mapper.upsertIfAdvances(memory);
        Optional<SessionMemory> after = findByConversationId(memory.getConversationId());
        if (before.isEmpty() && after.isPresent()) {
            return SaveResult.INSERTED;
        }
        long requestedSeq = memory.getLastSummarizedSeq() == null ? 0L : memory.getLastSummarizedSeq();
        long previousSeq = before.map(SessionMemory::getLastSummarizedSeq).orElse(0L);
        long currentSeq = after.map(SessionMemory::getLastSummarizedSeq).orElse(previousSeq);
        if (currentSeq > previousSeq || requestedSeq == currentSeq) {
            return SaveResult.ADVANCED;
        }
        return SaveResult.STALE_SKIPPED;
    }

    public void deleteByConversationId(String conversationId) {
        Optional<SessionMemory> existing = findByConversationId(conversationId);
        existing.ifPresent(m -> mapper.deleteById(m.getConversationId()));
    }

    public enum SaveResult {
        INSERTED,
        ADVANCED,
        STALE_SKIPPED
    }
}
