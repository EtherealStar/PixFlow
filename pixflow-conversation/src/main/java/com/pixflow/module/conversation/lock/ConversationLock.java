package com.pixflow.module.conversation.lock;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.conversation.config.ConversationProperties;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

public class ConversationLock {
    private final RedissonClient redissonClient;
    private final ConversationProperties properties;

    public ConversationLock(RedissonClient redissonClient, ConversationProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    public Optional<TurnLockHandle> tryLock(String conversationId) {
        RLock lock = redissonClient.getLock("lock:turn:" + conversationId);
        try {
            Duration waitTime = properties.getLock().getWaitTime();
            Duration ttl = properties.getLock().getTtl();
            boolean acquired = lock.tryLock(waitTime.toMillis(), ttl.toMillis(), TimeUnit.MILLISECONDS);
            return acquired ? Optional.of(new TurnLockHandle(lock)) : Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PixFlowException(ConversationErrorCode.LOCK_ACQUISITION_FAILED,
                    "interrupted while acquiring conversation lock", ex);
        }
    }
}
