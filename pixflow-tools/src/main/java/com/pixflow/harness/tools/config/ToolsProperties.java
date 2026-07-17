package com.pixflow.harness.tools.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.tools")
public class ToolsProperties {
    private int maxConcurrency = 8;

    private Result result = new Result();

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public Result getResult() {
        return result;
    }

    public void setResult(Result result) {
        this.result = result;
    }

    public static class Result {
        private int maxSizeChars = 50_000;

        private boolean persistWhenExceeded = true;

        private int previewChars = 4_000;

        public int getMaxSizeChars() {
            return maxSizeChars;
        }

        public void setMaxSizeChars(int maxSizeChars) {
            this.maxSizeChars = maxSizeChars;
        }

        public boolean isPersistWhenExceeded() {
            return persistWhenExceeded;
        }

        public void setPersistWhenExceeded(boolean persistWhenExceeded) {
            this.persistWhenExceeded = persistWhenExceeded;
        }

        public int getPreviewChars() {
            return previewChars;
        }

        public void setPreviewChars(int previewChars) {
            this.previewChars = previewChars;
        }
    }
}
