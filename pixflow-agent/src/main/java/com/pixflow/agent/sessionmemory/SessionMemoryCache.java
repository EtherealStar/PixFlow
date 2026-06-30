package com.pixflow.agent.sessionmemory;

import com.pixflow.agent.config.AgentProperties;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Session Memory Redis 缓存（事实源 + 缓存层）。
 *
 * <p>对应 {@code agent.md §7.2.2}：
 * <ul>
 *   <li>key: session:memory:{conversationId}</li>
 *   <li>TTL: 3600s（与 context.MessageChainCache 一致）</li>
 *   <li>写策略：先 MySQL，再刷 Redis 缓存</li>
 * </ul>
 */
@Component
public class SessionMemoryCache {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryCache.class);
    private static final String CACHE_KEY_PREFIX = "session:memory:";

    private final RedissonClient redissonClient;
    private final long ttlSeconds;

    public SessionMemoryCache(RedissonClient redissonClient, AgentProperties props) {
        this.redissonClient = redissonClient;
        this.ttlSeconds = props.getSessionMemory().getCache().getTtlSeconds();
    }

    public Optional<String> get(String conversationId) {
        try {
            Object value = redissonClient.getBucket(CACHE_KEY_PREFIX + conversationId).get();
            return Optional.ofNullable(value == null ? null : value.toString());
        } catch (Exception e) {
            log.debug("SessionMemoryCache: get failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void set(String conversationId, String content) {
        try {
            redissonClient.getBucket(CACHE_KEY_PREFIX + conversationId)
                    .set(content, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("SessionMemoryCache: set failed: {}", e.getMessage());
        }
    }

    public void invalidate(String conversationId) {
        try {
            redissonClient.getBucket(CACHE_KEY_PREFIX + conversationId).delete();
        } catch (Exception e) {
            log.debug("SessionMemoryCache: invalidate failed: {}", e.getMessage());
        }
    }
}