package com.pixflow.infra.cache.lock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.observability.NoopCacheMetrics;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

class RedissonLockTemplateTest {
    @Test
    void guardFailsAfterOwnerLosesLockAndTemplateDoesNotUnlock() throws Exception {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(client.getLock("test:task:1")).thenReturn(lock);
        when(lock.tryLock(10, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        RedissonLockTemplate template = new RedissonLockTemplate(client, new NoopCacheMetrics());

        assertThatThrownBy(() -> template.tryRunWithLock(
                new CacheKey("test:task:1", Duration.ofMinutes(1), "test"),
                Duration.ofMillis(10), LockGuard::assertHeld))
                .isInstanceOf(LockOwnershipLostException.class);

        verify(lock, never()).unlock();
    }

    @Test
    void templateAloneReleasesLockAfterOwnerAction() throws Exception {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(client.getLock("test:task:2")).thenReturn(lock);
        when(lock.tryLock(10, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        RedissonLockTemplate template = new RedissonLockTemplate(client, new NoopCacheMetrics());

        template.tryRunWithLock(new CacheKey("test:task:2", Duration.ofMinutes(1), "test"),
                Duration.ofMillis(10), LockGuard::assertHeld);

        verify(lock).unlock();
    }
}
