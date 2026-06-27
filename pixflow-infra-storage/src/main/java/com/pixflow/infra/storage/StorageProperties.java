package com.pixflow.infra.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * storage 配置入口。
 */
@ConfigurationProperties(prefix = "pixflow.storage")
public class StorageProperties {
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String region = "us-east-1";
    private boolean autoCreateBucket = true;
    private Duration presignTtl = Duration.ofMinutes(15);
    private DataSize uploadPartSize = DataSize.ofMegabytes(5);
    private DataSize maxBytesReadSize = DataSize.ofMegabytes(5);
    private int tmpExpiryDays = 1;
    private Buckets buckets = new Buckets();

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public boolean isAutoCreateBucket() {
        return autoCreateBucket;
    }

    public void setAutoCreateBucket(boolean autoCreateBucket) {
        this.autoCreateBucket = autoCreateBucket;
    }

    public Duration getPresignTtl() {
        return presignTtl;
    }

    public void setPresignTtl(Duration presignTtl) {
        this.presignTtl = presignTtl;
    }

    public DataSize getUploadPartSize() {
        return uploadPartSize;
    }

    public void setUploadPartSize(DataSize uploadPartSize) {
        this.uploadPartSize = uploadPartSize;
    }

    public DataSize getMaxBytesReadSize() {
        return maxBytesReadSize;
    }

    public void setMaxBytesReadSize(DataSize maxBytesReadSize) {
        this.maxBytesReadSize = maxBytesReadSize;
    }

    public int getTmpExpiryDays() {
        return tmpExpiryDays;
    }

    public void setTmpExpiryDays(int tmpExpiryDays) {
        this.tmpExpiryDays = tmpExpiryDays;
    }

    public Buckets getBuckets() {
        return buckets;
    }

    public void setBuckets(Buckets buckets) {
        this.buckets = buckets;
    }

    public static class Buckets {
        private String packages = "pixflow-packages";
        private String results = "pixflow-results";
        private String generated = "pixflow-generated";
        private String toolResults = "pixflow-tool-results";
        private String tmp = "pixflow-tmp";

        public String getPackages() {
            return packages;
        }

        public void setPackages(String packages) {
            this.packages = packages;
        }

        public String getResults() {
            return results;
        }

        public void setResults(String results) {
            this.results = results;
        }

        public String getGenerated() {
            return generated;
        }

        public void setGenerated(String generated) {
            this.generated = generated;
        }

        public String getToolResults() {
            return toolResults;
        }

        public void setToolResults(String toolResults) {
            this.toolResults = toolResults;
        }

        public String getTmp() {
            return tmp;
        }

        public void setTmp(String tmp) {
            this.tmp = tmp;
        }
    }
}
