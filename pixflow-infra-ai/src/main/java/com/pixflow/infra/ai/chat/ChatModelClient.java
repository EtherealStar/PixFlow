package com.pixflow.infra.ai.chat;

import reactor.core.publisher.Flux;

/**
 * 文本 chat 客户端。
 */
public interface ChatModelClient {
    ChatResult call(ChatRequest request);

    Flux<ChatStreamEvent> stream(ChatRequest request);
}
