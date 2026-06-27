package com.pixflow.infra.cache.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.cache")
public class CacheProperties {
    private Mode mode = Mode.SINGLE;
    private String address = "redis://127.0.0.1:6379";
    private String password;
    private String envPrefix = "dev";
    private Duration defaultTtl = Duration.ofHours(1);
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration timeout = Duration.ofSeconds(3);
    private int retryAttempts = 3;
    private Duration retryInterval = Duration.ofMillis(1500);
    private Pool pool = new Pool();
    private Lock lock = new Lock();
    private Semaphore semaphore = new Semaphore();

    public enum Mode {
        SINGLE,
        SENTINEL,
        CLUSTER
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEnvPrefix() {
        return envPrefix;
    }

    public void setEnvPrefix(String envPrefix) {
        this.envPrefix = envPrefix;
    }

    public Duration getDefaultTtl() {
        return defaultTtl;
    }

    public void setDefaultTtl(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getRetryAttempts() {
        return retryAttempts;
    }

    public void setRetryAttempts(int retryAttempts) {
        this.retryAttempts = retryAttempts;
    }

    public Duration getRetryInterval() {
        return retryInterval;
    }

    public void setRetryInterval(Duration retryInterval) {
        this.retryInterval = retryInterval;
    }

    public Pool getPool() {
        return pool;
    }

    public void setPool(Pool pool) {
        this.pool = pool;
    }

    public Lock getLock() {
        return lock;
    }

    public void setLock(Lock lock) {
        this.lock = lock;
    }

    public Semaphore getSemaphore() {
        return semaphore;
    }

    public void setSemaphore(Semaphore semaphore) {
        this.semaphore = semaphore;
    }

    public static class Pool {
        private int size = 16;
        private int minIdle = 4;

        public int getSize() {
            return size;
        }

        public void setSize(int size) {
            this.size = size;
        }

        public int getMinIdle() {
            return minIdle;
        }

        public void setMinIdle(int minIdle) {
            this.minIdle = minIdle;
        }
    }

    public static class Lock {
        private Duration watchdogTimeout = Duration.ofSeconds(30);

        public Duration getWatchdogTimeout() {
            return watchdogTimeout;
        }

        public void setWatchdogTimeout(Duration watchdogTimeout) {
            this.watchdogTimeout = watchdogTimeout;
        }
    }

    public static class Semaphore {
        private int defaultPermits = 1;
        private Duration defaultLeaseTime = Duration.ofMinutes(5);
        private Map<String, SemaphoreApi> apis = new LinkedHashMap<>();

        public int getDefaultPermits() {
            return defaultPermits;
        }

        public void setDefaultPermits(int defaultPermits) {
            this.defaultPermits = defaultPermits;
        }

        public Duration getDefaultLeaseTime() {
            return defaultLeaseTime;
        }

        public void setDefaultLeaseTime(Duration defaultLeaseTime) {
            this.defaultLeaseTime = defaultLeaseTime;
        }

        public Map<String, SemaphoreApi> getApis() {
            return apis;
        }

        public void setApis(Map<String, SemaphoreApi> apis) {
            this.apis = apis;
        }

        public SemaphoreApi resolve(String api) {
            SemaphoreApi configured = apis.get(api);
            if (configured != null) {
                configured.applyDefaults(defaultPermits, defaultLeaseTime);
                return configured;
            }
            SemaphoreApi fallback = new SemaphoreApi();
            fallback.setPermits(defaultPermits);
            fallback.setLeaseTime(defaultLeaseTime);
            return fallback;
        }
    }

    public static class SemaphoreApi {
        private int permits;
        private Duration leaseTime;

        public int getPermits() {
            return permits;
        }

        public void setPermits(int permits) {
            this.permits = permits;
        }

        public Duration getLeaseTime() {
            return leaseTime;
        }

        public void setLeaseTime(Duration leaseTime) {
            this.leaseTime = leaseTime;
        }

        private void applyDefaults(int defaultPermits, Duration defaultLeaseTime) {
            if (permits <= 0) {
                permits = defaultPermits;
            }
            if (leaseTime == null || leaseTime.isZero() || leaseTime.isNegative()) {
                leaseTime = defaultLeaseTime;
            }
        }
    }
}
