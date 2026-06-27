package com.pixflow.infra.ai.vision;

import com.pixflow.infra.ai.chat.ChatResult;

/**
 * 多模态理解客户端。
 */
public interface VisionModelClient {
    ChatResult call(VisionRequest request);
}
