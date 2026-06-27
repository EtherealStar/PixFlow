package com.pixflow.infra.ai.embedding;

import java.util.List;

/**
 * 文本 embedding 客户端。
 */
public interface EmbeddingClient {
    EmbeddingResult embed(List<String> texts);
}
