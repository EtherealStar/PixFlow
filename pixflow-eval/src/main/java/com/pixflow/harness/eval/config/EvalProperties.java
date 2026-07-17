package com.pixflow.harness.eval.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.eval")
public class EvalProperties {
    private boolean enabled = true;

    private Buffer buffer = new Buffer();

    private int columnExternalizeThreshold = 262_144;

    private int schemaVersion = 1;

    private Retention retention = new Retention();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Buffer getBuffer() {
        return buffer;
    }

    public void setBuffer(Buffer buffer) {
        this.buffer = buffer;
    }

    public int getColumnExternalizeThreshold() {
        return columnExternalizeThreshold;
    }

    public void setColumnExternalizeThreshold(int columnExternalizeThreshold) {
        this.columnExternalizeThreshold = columnExternalizeThreshold;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Retention getRetention() {
        return retention;
    }

    public void setRetention(Retention retention) {
        this.retention = retention;
    }

    public static class Buffer {
        private int capacity = 10_000;

        private int flushBatchSize = 200;

        private Duration flushInterval = Duration.ofSeconds(2);

        private int flushThreads = 1;

        private Duration drainTimeoutOnShutdown = Duration.ofSeconds(10);

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getFlushBatchSize() {
            return flushBatchSize;
        }

        public void setFlushBatchSize(int flushBatchSize) {
            this.flushBatchSize = flushBatchSize;
        }

        public Duration getFlushInterval() {
            return flushInterval;
        }

        public void setFlushInterval(Duration flushInterval) {
            this.flushInterval = flushInterval;
        }

        public int getFlushThreads() {
            return flushThreads;
        }

        public void setFlushThreads(int flushThreads) {
            this.flushThreads = flushThreads;
        }

        public Duration getDrainTimeoutOnShutdown() {
            return drainTimeoutOnShutdown;
        }

        public void setDrainTimeoutOnShutdown(Duration drainTimeoutOnShutdown) {
            this.drainTimeoutOnShutdown = drainTimeoutOnShutdown;
        }
    }

    public static class Retention {
        private int days = 7;

        private String cleanupCron = "0 30 3 * * *";

        private int cleanupBatchSize = 1000;

        public int getDays() {
            return days;
        }

        public void setDays(int days) {
            this.days = days;
        }

        public String getCleanupCron() {
            return cleanupCron;
        }

        public void setCleanupCron(String cleanupCron) {
            this.cleanupCron = cleanupCron;
        }

        public int getCleanupBatchSize() {
            return cleanupBatchSize;
        }

        public void setCleanupBatchSize(int cleanupBatchSize) {
            this.cleanupBatchSize = cleanupBatchSize;
        }
    }
}
