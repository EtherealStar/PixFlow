package com.pixflow.infra.auth;

import com.pixflow.infra.auth.config.AuthProperties;
import com.pixflow.infra.auth.throttle.LoginThrottleService;
import com.pixflow.infra.cache.counter.AtomicCounter;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.DefaultCacheNamespace;
import java.time.Duration;

class NoopLoginThrottleService extends LoginThrottleService {
    NoopLoginThrottleService() {
        super(new NoopCounter(), new DefaultCacheNamespace("test", Duration.ofMinutes(5)), new AuthProperties());
    }

    @Override
    public void assertAllowed(String username, String ipAddress) {
    }

    @Override
    public void recordFailureAndAssert(String username, String ipAddress) {
    }

    @Override
    public void clearUsername(String username) {
    }

    private static class NoopCounter implements AtomicCounter {
        @Override
        public long incrementBy(CacheKey key, long delta, Duration ttl) {
            return 0;
        }

        @Override
        public long get(CacheKey key) {
            return 0;
        }

        @Override
        public void reset(CacheKey key) {
        }
    }
}
