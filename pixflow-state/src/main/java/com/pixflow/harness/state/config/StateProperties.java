package com.pixflow.harness.state.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.state")
public class StateProperties {
    private final Progress progress = new Progress();

    private final Recovery recovery = new Recovery();

    private final Snapshot snapshot = new Snapshot();

    public Progress getProgress() {
        return progress;
    }

    public Recovery getRecovery() {
        return recovery;
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    public static class Progress {
        private boolean preferRedis = true;

        private long driftWarnThreshold = 5;

        public boolean isPreferRedis() {
            return preferRedis;
        }

        public void setPreferRedis(boolean preferRedis) {
            this.preferRedis = preferRedis;
        }

        public long getDriftWarnThreshold() {
            return driftWarnThreshold;
        }

        public void setDriftWarnThreshold(long driftWarnThreshold) {
            if (driftWarnThreshold < 0) {
                throw new IllegalArgumentException("driftWarnThreshold must not be negative");
            }
            this.driftWarnThreshold = driftWarnThreshold;
        }
    }

    public static class Recovery {
        private int runningScanLimit = 200;

        public int getRunningScanLimit() {
            return runningScanLimit;
        }

        public void setRunningScanLimit(int runningScanLimit) {
            if (runningScanLimit <= 0) {
                throw new IllegalArgumentException("runningScanLimit must be positive");
            }
            this.runningScanLimit = runningScanLimit;
        }
    }

    public static class Snapshot {
        private boolean includeProgress = true;

        public boolean isIncludeProgress() {
            return includeProgress;
        }

        public void setIncludeProgress(boolean includeProgress) {
            this.includeProgress = includeProgress;
        }
    }
}
