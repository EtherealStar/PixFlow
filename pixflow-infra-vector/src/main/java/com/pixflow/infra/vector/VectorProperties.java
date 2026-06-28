package com.pixflow.infra.vector;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.vector")
public class VectorProperties {
    private String host = "localhost";
    private int port = 6334;
    private boolean useTls = false;
    private String apiKey;
    private Duration timeout = Duration.ofSeconds(5);
    private boolean autoCreateCollection = true;
    private Retry retry = new Retry();

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isUseTls() {
        return useTls;
    }

    public void setUseTls(boolean useTls) {
        this.useTls = useTls;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        if (timeout == null) {
            throw new IllegalArgumentException("pixflow.vector.timeout must not be null");
        }
        this.timeout = timeout;
    }

    public boolean isAutoCreateCollection() {
        return autoCreateCollection;
    }

    public void setAutoCreateCollection(boolean autoCreateCollection) {
        this.autoCreateCollection = autoCreateCollection;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        if (retry == null) {
            throw new IllegalArgumentException("pixflow.vector.retry must not be null");
        }
        this.retry = retry;
    }

    public static class Retry {
        private int maxAttempts = 3;
        private Duration waitDuration = Duration.ofMillis(200);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("pixflow.vector.retry.max-attempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
        }

        public Duration getWaitDuration() {
            return waitDuration;
        }

        public void setWaitDuration(Duration waitDuration) {
            if (waitDuration == null) {
                throw new IllegalArgumentException("pixflow.vector.retry.wait-duration must not be null");
            }
            this.waitDuration = waitDuration;
        }
    }
}
