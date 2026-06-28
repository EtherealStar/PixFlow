package com.pixflow.module.commerce.config;

import com.pixflow.module.commerce.importer.CategoryConflictPolicy;
import com.pixflow.module.commerce.query.CommerceSourceScope;
import com.pixflow.module.commerce.query.PeriodType;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.commerce")
public class CommerceProperties {
    private final Import importConfig = new Import();
    private final Query query = new Query();
    private final Source source = new Source();

    public Import getImport() {
        return importConfig;
    }

    public Query getQuery() {
        return query;
    }

    public Source getSource() {
        return source;
    }

    public static class Import {
        private int batchSize = 500;
        private boolean strictHeader = true;
        private CategoryConflictPolicy categoryConflict = CategoryConflictPolicy.WARN;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public boolean isStrictHeader() {
            return strictHeader;
        }

        public void setStrictHeader(boolean strictHeader) {
            this.strictHeader = strictHeader;
        }

        public CategoryConflictPolicy getCategoryConflict() {
            return categoryConflict;
        }

        public void setCategoryConflict(CategoryConflictPolicy categoryConflict) {
            this.categoryConflict = categoryConflict;
        }
    }

    public static class Query {
        private int defaultWindowDays = 30;
        private int benchmarkMinSample = 5;
        private PeriodType defaultPeriodType = PeriodType.DAY;
        private CommerceSourceScope defaultSourceScope = CommerceSourceScope.ALL;

        public int getDefaultWindowDays() {
            return defaultWindowDays;
        }

        public void setDefaultWindowDays(int defaultWindowDays) {
            this.defaultWindowDays = defaultWindowDays;
        }

        public int getBenchmarkMinSample() {
            return benchmarkMinSample;
        }

        public void setBenchmarkMinSample(int benchmarkMinSample) {
            this.benchmarkMinSample = benchmarkMinSample;
        }

        public PeriodType getDefaultPeriodType() {
            return defaultPeriodType;
        }

        public void setDefaultPeriodType(PeriodType defaultPeriodType) {
            this.defaultPeriodType = defaultPeriodType;
        }

        public CommerceSourceScope getDefaultSourceScope() {
            return defaultSourceScope;
        }

        public void setDefaultSourceScope(CommerceSourceScope defaultSourceScope) {
            this.defaultSourceScope = defaultSourceScope;
        }
    }

    public static class Source {
        private boolean liveEnabled;
        private Duration freshnessTtl = Duration.ofHours(6);
        private String platform = "fake";
        private Duration timeout = Duration.ofSeconds(2);
        private int maxRetries = 1;
        private boolean fallbackToStored = true;

        public boolean isLiveEnabled() {
            return liveEnabled;
        }

        public void setLiveEnabled(boolean liveEnabled) {
            this.liveEnabled = liveEnabled;
        }

        public Duration getFreshnessTtl() {
            return freshnessTtl;
        }

        public void setFreshnessTtl(Duration freshnessTtl) {
            this.freshnessTtl = freshnessTtl;
        }

        public String getPlatform() {
            return platform;
        }

        public void setPlatform(String platform) {
            this.platform = platform;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public boolean isFallbackToStored() {
            return fallbackToStored;
        }

        public void setFallbackToStored(boolean fallbackToStored) {
            this.fallbackToStored = fallbackToStored;
        }
    }
}
