package com.pixflow.harness.permission.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pixflow.permission")
public class PermissionProperties {
    /**
     * 图片 x 支路总数超过该值时需要 BULK 确认令牌。
     */
    private int bulkThreshold = 500;

    public int getBulkThreshold() {
        return bulkThreshold;
    }

    public void setBulkThreshold(int bulkThreshold) {
        this.bulkThreshold = bulkThreshold;
    }
}
