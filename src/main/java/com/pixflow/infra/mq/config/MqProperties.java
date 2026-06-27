package com.pixflow.infra.mq.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.mq")
public class MqProperties {
    private Duration publishConfirmTimeout = Duration.ofSeconds(5);
    private int prefetch = 1;
    private int consumerConcurrency = 4;
    private int inProcessRetries = 2;
    private int maxRetries = 5;
    private List<Duration> retryBackoff = new ArrayList<>(List.of(
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofMinutes(30)));
    private int dlqAlertThreshold = 1;

    public Duration getPublishConfirmTimeout() {
        return publishConfirmTimeout;
    }

    public void setPublishConfirmTimeout(Duration publishConfirmTimeout) {
        this.publishConfirmTimeout = publishConfirmTimeout;
    }

    public int getPrefetch() {
        return prefetch;
    }

    public void setPrefetch(int prefetch) {
        this.prefetch = prefetch;
    }

    public int getConsumerConcurrency() {
        return consumerConcurrency;
    }

    public void setConsumerConcurrency(int consumerConcurrency) {
        this.consumerConcurrency = consumerConcurrency;
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

    public int getDlqAlertThreshold() {
        return dlqAlertThreshold;
    }

    public void setDlqAlertThreshold(int dlqAlertThreshold) {
        this.dlqAlertThreshold = dlqAlertThreshold;
    }
}
