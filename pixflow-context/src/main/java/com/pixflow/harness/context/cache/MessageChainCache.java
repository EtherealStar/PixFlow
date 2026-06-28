package com.pixflow.harness.context.cache;

import com.pixflow.harness.context.model.Message;
import java.util.List;
import java.util.Optional;

public interface MessageChainCache {
    Optional<List<Message>> get(String conversationId);

    void put(String conversationId, List<Message> messages);

    void evict(String conversationId);
}
