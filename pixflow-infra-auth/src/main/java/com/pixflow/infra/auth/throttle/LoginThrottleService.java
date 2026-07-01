package com.pixflow.infra.auth.throttle;

import com.pixflow.infra.auth.config.AuthProperties;
import com.pixflow.infra.auth.error.AuthErrorCode;
import com.pixflow.infra.auth.error.AuthException;
import com.pixflow.infra.cache.counter.AtomicCounter;
import com.pixflow.infra.cache.key.CacheNamespace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

public class LoginThrottleService {
    private final AtomicCounter counter;
    private final CacheNamespace namespace;
    private final AuthProperties properties;

    public LoginThrottleService(AtomicCounter counter, CacheNamespace namespace, AuthProperties properties) {
        this.counter = counter;
        this.namespace = namespace;
        this.properties = properties;
    }

    public void assertAllowed(String username, String ipAddress) {
        long userFailures = counter.get(namespace.key("auth", "fail", username));
        long ipFailures = counter.get(namespace.key("auth", "fail-ip", keySafe(ipAddress)));
        if (userFailures >= properties.getThrottle().getMaxFailures() || ipFailures >= properties.getThrottle().getMaxFailures()) {
            throw new AuthException(AuthErrorCode.AUTH_TOO_MANY_ATTEMPTS, "登录失败次数过多，请稍后再试", properties.getThrottle().getBlockTtl());
        }
    }

    public void recordFailure(String username, String ipAddress) {
        Duration ttl = properties.getThrottle().getWindow();
        counter.incrementBy(namespace.key("auth", "fail", username), 1, ttl);
        counter.incrementBy(namespace.key("auth", "fail-ip", keySafe(ipAddress)), 1, ttl);
    }

    public void clear(String username, String ipAddress) {
        counter.reset(namespace.key("auth", "fail", username));
        counter.reset(namespace.key("auth", "fail-ip", keySafe(ipAddress)));
    }

    private static String keySafe(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash throttle key", ex);
        }
    }
}
