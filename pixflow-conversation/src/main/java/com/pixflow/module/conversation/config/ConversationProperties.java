package com.pixflow.module.conversation.config;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.conversation")
public class ConversationProperties {
    private final Sse sse = new Sse();
    private final Lock lock = new Lock();
    private final Confirmation confirmation = new Confirmation();
    private final History history = new History();
    private final Attachment attachment = new Attachment();
    private final Progress progress = new Progress();
    private final TurnExecutor turnExecutor = new TurnExecutor();

    public Sse getSse() {
        return sse;
    }

    public Lock getLock() {
        return lock;
    }

    public Confirmation getConfirmation() {
        return confirmation;
    }

    public History getHistory() {
        return history;
    }

    public Attachment getAttachment() {
        return attachment;
    }

    public Progress getProgress() {
        return progress;
    }

    public TurnExecutor getTurnExecutor() {
        return turnExecutor;
    }

    @PostConstruct
    void validate() {
        if (sse.timeout == null || sse.timeout.isZero() || sse.timeout.isNegative()) {
            throw new IllegalStateException("pixflow.conversation.sse.timeout must be positive");
        }
        if (sse.heartbeatInterval == null || sse.heartbeatInterval.isNegative()) {
            throw new IllegalStateException("pixflow.conversation.sse.heartbeat-interval must not be negative");
        }
        if (lock.waitTime == null || lock.waitTime.isNegative()) {
            throw new IllegalStateException("pixflow.conversation.lock.wait-time must not be negative");
        }
        if (turnExecutor.maxConcurrency < 1) {
            throw new IllegalStateException("pixflow.conversation.turn-executor.max-concurrency must be >= 1");
        }
        if (turnExecutor.keepAlive == null || turnExecutor.keepAlive.isZero() || turnExecutor.keepAlive.isNegative()) {
            throw new IllegalStateException("pixflow.conversation.turn-executor.keep-alive must be positive");
        }
        if (confirmation.batchThreshold < 1) {
            throw new IllegalStateException("pixflow.conversation.confirmation.batch-threshold must be >= 1");
        }
        if (confirmation.challengeTtl == null || confirmation.challengeTtl.isZero()
                || confirmation.challengeTtl.isNegative()) {
            throw new IllegalStateException("pixflow.conversation.confirmation.challenge-ttl must be positive");
        }
        if (confirmation.tokenTtl == null || confirmation.tokenTtl.isZero()
                || confirmation.tokenTtl.isNegative()) {
            throw new IllegalStateException("pixflow.conversation.confirmation.token-ttl must be positive");
        }
        confirmation.permitLiteralAnswers = confirmation.permitLiteralAnswers == null
                ? List.of()
                : confirmation.permitLiteralAnswers.stream()
                        .filter(answer -> answer != null && !answer.isBlank())
                        .map(String::trim)
                        .distinct()
                        .collect(Collectors.toUnmodifiableList());
        if (confirmation.permitLiteralAnswers.isEmpty()) {
            throw new IllegalStateException("pixflow.conversation.confirmation.permit-literal-answers must not be empty");
        }
        if (history.maxPageSize < 1) {
            throw new IllegalStateException("pixflow.conversation.history.max-page-size must be >= 1");
        }
        if (history.defaultPageSize < 1 || history.defaultPageSize > history.maxPageSize) {
            throw new IllegalStateException(
                    "pixflow.conversation.history.default-page-size must be in [1, max-page-size]");
        }
    }

    public static class Sse {
        private Duration timeout = Duration.ofMinutes(5);
        private Duration heartbeatInterval = Duration.ofSeconds(30);

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Duration getHeartbeatInterval() {
            return heartbeatInterval;
        }

        public void setHeartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = heartbeatInterval;
        }
    }

    public static class Lock {
        /**
         * @deprecated 由 Redisson 全局 lockWatchdogTimeout 兜底,本字段不再生效。
         *             保留仅为兼容老 application.yml;新部署应删除。
         */
        @Deprecated
        private Duration ttl = Duration.ofSeconds(60);
        private Duration waitTime = Duration.ofSeconds(2);

        /**
         * @deprecated 见 {@link #ttl} 字段说明。
         */
        @Deprecated
        public Duration getTtl() {
            return ttl;
        }

        /**
         * @deprecated 见 {@link #ttl} 字段说明。
         */
        @Deprecated
        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public Duration getWaitTime() {
            return waitTime;
        }

        public void setWaitTime(Duration waitTime) {
            this.waitTime = waitTime;
        }
    }

    public static class Confirmation {
        private int batchThreshold = 50;
        private Duration challengeTtl = Duration.ofMinutes(5);
        private Duration tokenTtl = Duration.ofMinutes(10);
        private List<String> permitLiteralAnswers = List.of("按实际", "按实际处理", "确认", "已确认");

        public int getBatchThreshold() {
            return batchThreshold;
        }

        public void setBatchThreshold(int batchThreshold) {
            this.batchThreshold = batchThreshold;
        }

        public Duration getChallengeTtl() {
            return challengeTtl;
        }

        public void setChallengeTtl(Duration challengeTtl) {
            this.challengeTtl = challengeTtl;
        }

        public Duration getTokenTtl() {
            return tokenTtl;
        }

        public void setTokenTtl(Duration tokenTtl) {
            this.tokenTtl = tokenTtl;
        }

        public List<String> getPermitLiteralAnswers() {
            return permitLiteralAnswers;
        }

        public void setPermitLiteralAnswers(List<String> permitLiteralAnswers) {
            this.permitLiteralAnswers = permitLiteralAnswers;
        }
    }

    public static class History {
        private int defaultPageSize = 50;
        private int maxPageSize = 200;

        public int getDefaultPageSize() {
            return defaultPageSize;
        }

        public void setDefaultPageSize(int defaultPageSize) {
            this.defaultPageSize = defaultPageSize;
        }

        public int getMaxPageSize() {
            return maxPageSize;
        }

        public void setMaxPageSize(int maxPageSize) {
            this.maxPageSize = maxPageSize;
        }
    }

    public static class Attachment {
        private int maxImageSizeMb = 20;
        private List<String> allowedImageMime = List.of("image/jpeg", "image/png", "image/webp");

        public int getMaxImageSizeMb() {
            return maxImageSizeMb;
        }

        public void setMaxImageSizeMb(int maxImageSizeMb) {
            this.maxImageSizeMb = maxImageSizeMb;
        }

        public List<String> getAllowedImageMime() {
            return allowedImageMime;
        }

        public void setAllowedImageMime(List<String> allowedImageMime) {
            this.allowedImageMime = allowedImageMime;
        }
    }

    public static class Progress {
        private String topicPrefix = "task-progress";

        public String getTopicPrefix() {
            return topicPrefix;
        }

        public void setTopicPrefix(String topicPrefix) {
            this.topicPrefix = topicPrefix;
        }
    }

    public static class TurnExecutor {
        private int maxConcurrency = 16;
        private Duration keepAlive = Duration.ofSeconds(60);

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        public Duration getKeepAlive() {
            return keepAlive;
        }

        public void setKeepAlive(Duration keepAlive) {
            this.keepAlive = keepAlive;
        }
    }
}
