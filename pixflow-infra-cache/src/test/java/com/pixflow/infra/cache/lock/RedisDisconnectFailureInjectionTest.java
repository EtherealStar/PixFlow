package com.pixflow.infra.cache.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.cache.error.CacheException;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.DefaultCacheNamespace;
import com.pixflow.infra.cache.observability.NoopCacheMetrics;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;

class RedisDisconnectFailureInjectionTest {
    @Test
    void disconnectedRedisFailsClosedWithoutRunningProtectedAction() {
        try (var redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)) {
            redis.start();
            Config config = new Config();
            config.useSingleServer()
                    .setAddress("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379))
                    .setRetryAttempts(0)
                    .setConnectTimeout(1_000)
                    .setTimeout(1_000);
            RedissonClient client = Redisson.create(config);
            try {
                var locks = new RedissonLockTemplate(client, new NoopCacheMetrics());
                CacheKey key = new DefaultCacheNamespace("test", Duration.ofMinutes(1))
                        .key("lock", "disconnected");
                AtomicBoolean actionRan = new AtomicBoolean();

                // 先建立真实连接再停止 Redis，验证网络断开时不会退化为仅依赖 execution epoch 执行。
                client.getBucket("connection-probe").set("ok");
                redis.stop();

                assertThatThrownBy(() -> locks.tryRunWithLock(
                        key, Duration.ofMillis(100), guard -> actionRan.set(true)))
                        .isInstanceOf(CacheException.class);
                assertThat(actionRan).isFalse();
            } finally {
                client.shutdown();
            }
        }
    }
}
