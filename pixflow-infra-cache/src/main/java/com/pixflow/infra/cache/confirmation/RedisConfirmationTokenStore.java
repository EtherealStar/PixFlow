package com.pixflow.infra.cache.confirmation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.contracts.confirmation.ConfirmationTokenStore;
import com.pixflow.contracts.confirmation.TokenClaims;
import com.pixflow.infra.cache.error.CacheErrorCode;
import com.pixflow.infra.cache.error.CacheException;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.observability.CacheMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

public class RedisConfirmationTokenStore implements ConfirmationTokenStore {
    private static final String ATOMIC_GET_AND_DELETE = """
            local value = redis.call('GET', KEYS[1])
            if value then
              redis.call('DEL', KEYS[1])
            end
            return value
            """;

    private final RedissonClient redissonClient;
    private final CacheNamespace namespace;
    private final ObjectMapper objectMapper;
    private final CacheMetrics metrics;

    public RedisConfirmationTokenStore(
            RedissonClient redissonClient,
            CacheNamespace namespace,
            ObjectMapper objectMapper,
            CacheMetrics metrics) {
        this.redissonClient = redissonClient;
        this.namespace = namespace;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Override
    public void save(String tokenId, TokenClaims claims, Duration ttl) {
        try {
            String key = namespace.key("confirmation", tokenId).value();
            String json = objectMapper.writeValueAsString(claims);
            redissonClient.<String>getBucket(key, StringCodec.INSTANCE).set(json, ttl.toMillis(), TimeUnit.MILLISECONDS);
            metrics.recordConfirmationToken("save", "ok");
        } catch (JsonProcessingException ex) {
            metrics.recordConfirmationToken("save", "serialization_error");
            throw new CacheException(CacheErrorCode.CACHE_SERIALIZATION_FAILED, "confirmation_save", "confirmation", "确认令牌序列化失败", ex);
        } catch (RuntimeException ex) {
            metrics.recordConfirmationToken("save", "error");
            throw new CacheException(CacheErrorCode.CACHE_CONFIRMATION_TOKEN_FAILED, "confirmation_save", "confirmation", "确认令牌保存失败", ex);
        }
    }

    @Override
    public Optional<TokenClaims> consume(String tokenId) {
        try {
            String key = namespace.key("confirmation", tokenId).value();
            // 确认令牌必须一次性消费，Lua 保证 GET 和 DEL 在 Redis 内原子完成。
            String json = redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    ATOMIC_GET_AND_DELETE,
                    RScript.ReturnType.VALUE,
                    List.of(key));
            if (json == null || json.isBlank()) {
                metrics.recordConfirmationToken("consume", "miss");
                return Optional.empty();
            }
            metrics.recordConfirmationToken("consume", "hit");
            return Optional.of(objectMapper.readValue(json, TokenClaims.class));
        } catch (JsonProcessingException ex) {
            metrics.recordConfirmationToken("consume", "serialization_error");
            throw new CacheException(CacheErrorCode.CACHE_SERIALIZATION_FAILED, "confirmation_consume", "confirmation", "确认令牌反序列化失败", ex);
        } catch (RuntimeException ex) {
            metrics.recordConfirmationToken("consume", "error");
            throw new CacheException(CacheErrorCode.CACHE_CONFIRMATION_TOKEN_FAILED, "confirmation_consume", "confirmation", "确认令牌消费失败", ex);
        }
    }
}
