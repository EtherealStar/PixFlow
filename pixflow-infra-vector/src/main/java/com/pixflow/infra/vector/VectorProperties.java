package com.pixflow.infra.vector;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.vector")
public class VectorProperties {
    private Qdrant qdrant = new Qdrant();

    public Qdrant getQdrant() {
        return qdrant;
    }

    public void setQdrant(Qdrant qdrant) {
        if (qdrant == null) {
            throw new IllegalArgumentException("pixflow.vector.qdrant must not be null");
        }
        this.qdrant = qdrant;
    }

    public static class Qdrant {
        private String host = "localhost";

        private int grpcPort = 6334;

        private boolean useTls;

        private String apiKey;

        private Duration timeout = Duration.ofSeconds(5);

        private Retry retry = new Retry();

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getGrpcPort() {
            return grpcPort;
        }

        public void setGrpcPort(int grpcPort) {
            if (grpcPort <= 0 || grpcPort > 65535) {
                throw new IllegalArgumentException("pixflow.vector.qdrant.grpc-port must be valid");
            }
            this.grpcPort = grpcPort;
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
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("pixflow.vector.qdrant.timeout must be positive");
            }
            this.timeout = timeout;
        }

        public Retry getRetry() {
            return retry;
        }

        public void setRetry(Retry retry) {
            if (retry == null) {
                throw new IllegalArgumentException("pixflow.vector.qdrant.retry must not be null");
            }
            this.retry = retry;
        }
    }

    public static class Retry {
        private int maxAttempts = 3;

        private Duration waitDuration = Duration.ofMillis(200);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("pixflow.vector.qdrant.retry.max-attempts must be >= 1");
            }
            this.maxAttempts = maxAttempts;
        }

        public Duration getWaitDuration() {
            return waitDuration;
        }

        public void setWaitDuration(Duration waitDuration) {
            if (waitDuration == null || waitDuration.isNegative()) {
                throw new IllegalArgumentException("pixflow.vector.qdrant.retry.wait-duration must not be negative");
            }
            this.waitDuration = waitDuration;
        }
    }
}
