package com.pixflow.module.memory.skuhistory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("sku_history")
public class SkuHistory {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String skuId;

    private String taskId;

    private String paramsJson;

    private String metricsBefore;

    private String metricsAfter;

    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSkuId() {
        return skuId;
    }

    public void setSkuId(String skuId) {
        this.skuId = skuId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getParamsJson() {
        return paramsJson;
    }

    public void setParamsJson(String paramsJson) {
        this.paramsJson = paramsJson;
    }

    public String getMetricsBefore() {
        return metricsBefore;
    }

    public void setMetricsBefore(String metricsBefore) {
        this.metricsBefore = metricsBefore;
    }

    public String getMetricsAfter() {
        return metricsAfter;
    }

    public void setMetricsAfter(String metricsAfter) {
        this.metricsAfter = metricsAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
