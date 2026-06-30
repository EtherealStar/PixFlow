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

    public void upsert(SessionMemory memory) {
        if (findByConversationId(memory.getConversationId()).isPresent()) {
            mapper.updateById(memory);
        } else {
            mapper.insert(memory);
        }
    }

    public void deleteByConversationId(String conversationId) {
        Optional<SessionMemory> existing = findByConversationId(conversationId);
        existing.ifPresent(m -> mapper.deleteById(m.getConversationId()));
    }
}