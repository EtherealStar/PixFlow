package com.etherealstar.pixflow.module.dag.schema;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DAG 校验相关配置（需求 7.6）。
 *
 * <p>绑定 {@code pixflow.dag.*} 配置项。当前仅包含节点数量上限，默认 50，
 * 由 {@code com.etherealstar.pixflow.module.dag.validator.DagValidator} 在节点数校验时读取。</p>
 */
@ConfigurationProperties(prefix = "pixflow.dag")
public class DagValidationProperties {

    /** 单个 DAG 允许的最大节点数（需求 7.6，默认 50，取值范围 1–maxNodeCount）。 */
    private int maxNodeCount = 50;

    public int getMaxNodeCount() {
        return maxNodeCount;
    }

    public void setMaxNodeCount(int maxNodeCount) {
        this.maxNodeCount = maxNodeCount;
    }
}
