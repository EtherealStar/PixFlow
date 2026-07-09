package com.pixflow.module.conversation.lock;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.module.conversation.config.ConversationProperties;
import com.pixflow.module.conversation.error.ConversationErrorCode;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/**
 * 会话级锁：保证同一会话同一时刻只有一个 agent 回合在跑。
 *
 * <p>看门狗语义：不传 {@code leaseTime}，由 Redisson 全局 {@code lockWatchdogTimeout}
 * 负责自动续期。看门狗失效（客户端崩溃、网络分区）时锁自然过期，下一轮回合可获取。
 * 续期间隔为 {@code lockWatchdogTimeout / 3}，无法在业务层配置。
 *
 * <p>读 {@code lock.waitTime}：受 Redisson 单 key tryLock 语义约束，到点未取到立即放弃，
 * 由调用方根据 {@link PixFlowException} 决定是否回退/重试。
 */
public class ConversationLock {
    private final RedissonClient redissonClient;
    private final ConversationProperties properties;

    public ConversationLock(RedissonClient redissonClient, ConversationProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    public Optional<TurnLockHandle> tryLock(String conversationId) {
        if (redissonClient == null) {
            throw new PixFlowException(ConversationErrorCode.LOCK_ACQUISITION_FAILED,
                    "conversation lock backend is not configured");
        }
        RLock lock = redissonClient.getLock("lock:turn:" + conversationId);
        boolean acquired;
        try {
            Duration waitTime = properties.getLock().getWaitTime();
            // 不传 leaseTime：触发 Redisson 看门狗（按 Config.lockWatchdogTimeout 自动续期）。
            acquired = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PixFlowException(ConversationErrorCode.LOCK_ACQUISITION_FAILED,
                    "interrupted while acquiring conversation lock", ex);
        }
        return acquired ? Optional.of(new TurnLockHandle(lock)) : Optional.empty();
    }
}
