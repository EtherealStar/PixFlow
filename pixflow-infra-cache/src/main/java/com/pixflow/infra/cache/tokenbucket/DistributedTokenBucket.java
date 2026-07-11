package com.pixflow.infra.cache.tokenbucket;

import com.pixflow.infra.cache.key.CacheKey;

public interface DistributedTokenBucket {
    TokenBucketDecision tryConsume(CacheKey key, TokenBucketPolicy policy, long cost);
}
