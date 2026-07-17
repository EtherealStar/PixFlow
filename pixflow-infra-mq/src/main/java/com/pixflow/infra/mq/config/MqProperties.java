package com.pixflow.infra.mq.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.mq")
public class MqProperties {
    private String namesrvAddr = "localhost:9876";

    private String producerGroup = "pixflow-producer";

    private Duration sendTimeout = Duration.ofSeconds(5);

    private final Consumer consumer = new Consumer();

    private int inProcessRetries = 2;

    private int maxRetries = 3;

    private List<Duration> retryBackoff = new ArrayList<>(List.of(
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2)));

    private RetryMode retryMode = RetryMode.BROKER;

    private int dlqAlertThreshold = 1;

    private boolean topicAutoCreate;

    public String getNamesrvAddr() {
        return namesrvAddr;
    }

    public void setNamesrvAddr(String namesrvAddr) {
        this.namesrvAddr = namesrvAddr;
    }

    public String getProducerGroup() {
        return producerGroup;
    }

    public void setProducerGroup(String producerGroup) {
        this.producerGroup = producerGroup;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    public Consumer getConsumer() {
        return consumer;
    }

    public int getInProcessRetries() {
        return inProcessRetries;
    }

    public void setInProcessRetries(int inProcessRetries) {
        this.inProcessRetries = inProcessRetries;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public List<Duration> getRetryBackoff() {
        return retryBackoff;
    }

    public void setRetryBackoff(List<Duration> retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    public RetryMode getRetryMode() {
        return retryMode;
    }

    public void setRetryMode(RetryMode retryMode) {
        this.retryMode = retryMode;
    }

    public int getDlqAlertThreshold() {
        return dlqAlertThreshold;
    }

    public void setDlqAlertThreshold(int dlqAlertThreshold) {
        this.dlqAlertThreshold = dlqAlertThreshold;
    }

    public boolean isTopicAutoCreate() {
        return topicAutoCreate;
    }

    public void setTopicAutoCreate(boolean topicAutoCreate) {
        this.topicAutoCreate = topicAutoCreate;
    }

    public static class Consumer {
        private int consumeThreadMin = 2;

        private int consumeThreadMax = 8;

        private Duration consumeTimeout = Duration.ofMinutes(5);

        public int getConsumeThreadMin() {
            return consumeThreadMin;
        }

        public void setConsumeThreadMin(int consumeThreadMin) {
            this.consumeThreadMin = consumeThreadMin;
        }

        public int getConsumeThreadMax() {
            return consumeThreadMax;
        }

        public void setConsumeThreadMax(int consumeThreadMax) {
            this.consumeThreadMax = consumeThreadMax;
        }

        public Duration getConsumeTimeout() {
            return consumeTimeout;
        }

        public void setConsumeTimeout(Duration consumeTimeout) {
            this.consumeTimeout = consumeTimeout;
        }
    }

    public enum RetryMode {
        BROKER,
        EXPLICIT_DELAY
    }
}
