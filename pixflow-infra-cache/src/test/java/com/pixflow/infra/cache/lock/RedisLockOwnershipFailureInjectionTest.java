package com.pixflow.infra.cache.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.DefaultCacheNamespace;
import com.pixflow.infra.cache.observability.NoopCacheMetrics;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RedisLockOwnershipFailureInjectionTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private static RedissonClient redissonClient;

    @BeforeAll
    static void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redissonClient = Redisson.create(config);
    }

    @AfterAll
    static void tearDown() {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
    }

    @Test
    void oldOwnerGuardRejectsCommitAfterLockKeyIsLostAndTakenOver() throws Exception {
        var locks = new RedissonLockTemplate(redissonClient, new NoopCacheMetrics());
        CacheKey key = new DefaultCacheNamespace("test", Duration.ofMinutes(1))
                .key("lock", "task", UUID.randomUUID().toString());
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch takeoverCompleted = new CountDownLatch(1);
        CountDownLatch oldOwnerChecked = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var oldOwner = pool.submit(() -> locks.tryRunWithLock(key, Duration.ofSeconds(1), guard -> {
                ownerEntered.countDown();
                await(takeoverCompleted);
                // 模拟 Redis 故障导致 watchdog 锁键丢失：旧 owner 即使线程仍活着，也不能继续提交。
                assertThatThrownBy(guard::assertHeld).isInstanceOf(LockOwnershipLostException.class);
                oldOwnerChecked.countDown();
            }));

            assertThat(ownerEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(redissonClient.getKeys().delete(key.value())).isEqualTo(1);

            assertThat(locks.tryRunWithLock(key, Duration.ofSeconds(1), newOwnerGuard -> {
                newOwnerGuard.assertHeld();
                takeoverCompleted.countDown();
                await(oldOwnerChecked);
            })).isTrue();
            assertThat(oldOwner.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            takeoverCompleted.countDown();
            oldOwnerChecked.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待故障注入同步点超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
