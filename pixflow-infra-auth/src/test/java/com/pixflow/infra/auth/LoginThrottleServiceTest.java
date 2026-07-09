package com.pixflow.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.auth.config.AuthProperties;
import com.pixflow.infra.auth.error.AuthErrorCode;
import com.pixflow.infra.auth.error.AuthException;
import com.pixflow.infra.auth.throttle.LoginThrottleService;
import com.pixflow.infra.cache.counter.AtomicCounter;
import com.pixflow.infra.cache.key.CacheKey;
import com.pixflow.infra.cache.key.DefaultCacheNamespace;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class LoginThrottleServiceTest {
    @Test
    void failureIncrementCanLockImmediately() {
        LoginThrottleService service = new LoginThrottleService(
                new InMemoryCounter(),
                new DefaultCacheNamespace("test", Duration.ofMinutes(5)),
                properties(2));

        service.recordFailureAndAssert("alice", "127.0.0.1");

        assertThatThrownBy(() -> service.recordFailureAndAssert("alice", "127.0.0.1"))
                .isInstanceOfSatisfying(AuthException.class, ex ->
                        assertThat(ex.code()).isEqualTo(AuthErrorCode.AUTH_TOO_MANY_ATTEMPTS));
    }

    @Test
    void successfulLoginClearsUsernameOnly() {
        InMemoryCounter counter = new InMemoryCounter();
        LoginThrottleService service = new LoginThrottleService(
                counter,
                new DefaultCacheNamespace("test", Duration.ofMinutes(5)),
                properties(5));

        service.recordFailureAndAssert("alice", "127.0.0.1");
        service.clearUsername("alice");

        assertThat(counter.keys()).noneMatch(key -> key.contains(":auth:fail:alice"));
        assertThat(counter.keys()).anyMatch(key -> key.contains(":auth:fail-ip:"));
    }

    private static AuthProperties properties(int maxFailures) {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setSecret("test-secret-with-more-than-32-bytes");
        properties.getThrottle().setMaxFailures(maxFailures);
        return properties;
    }

    private static final class InMemoryCounter implements AtomicCounter {
        private final Map<String, Long> counts = new ConcurrentHashMap<>();

        @Override
        public long incrementBy(CacheKey key, long delta, Duration ttl) {
            return counts.merge(key.value(), delta, Long::sum);
        }

        @Override
        public long get(CacheKey key) {
            return counts.getOrDefault(key.value(), 0L);
        }

        @Override
        public void reset(CacheKey key) {
            counts.remove(key.value());
        }

        Iterable<String> keys() {
            return counts.keySet();
        }
    }
}
