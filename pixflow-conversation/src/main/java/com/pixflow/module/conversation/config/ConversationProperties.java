package com.pixflow.module.conversation.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.conversation")
public class ConversationProperties {
    private final Sse sse = new Sse();
    private final Lock lock = new Lock();
    private final Confirmation confirmation = new Confirmation();
    private final History history = new History();
    private final Attachment attachment = new Attachment();
    private final Progress progress = new Progress();

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
        private Duration ttl = Duration.ofSeconds(60);
        private Duration waitTime = Duration.ofSeconds(2);

        public Duration getTtl() {
            return ttl;
        }

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
}
