package com.pixflow.infra.ai.rerank;

import java.util.List;

/**
 * 查询重排客户端。
 */
public interface RerankClient {
    RerankResult rerank(String query, List<String> candidates);
}
