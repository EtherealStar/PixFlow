package com.pixflow.module.dag.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * DagProperties 默认值与 setter 测试。
 */
class DagPropertiesTest {

    @Test
    void defaults_areSane() {
        DagProperties props = new DagProperties();
        assertThat(props.getValidate().getMaxNodes()).isEqualTo(50);
        assertThat(props.getValidate().getMinNodes()).isEqualTo(1);
        assertThat(props.getExecution().getUnitTimeout().toSeconds()).isEqualTo(60);
        assertThat(props.getExecution().getSourceBytesLimit()).isEqualTo(209715200L);
        assertThat(props.getAssetCache().getMaxEntriesPerTask()).isEqualTo(5);
        assertThat(props.getAssetCache().isEnabled()).isTrue();
        assertThat(props.getPendingPlan().getTtl().toMinutes()).isEqualTo(30);
        assertThat(props.getGroupCache().getRefTtl().toHours()).isEqualTo(2);
    }

    @Test
    void setters_apply() {
        DagProperties props = new DagProperties();
        props.getValidate().setMaxNodes(100);
        props.getExecution().setUnitTimeout(java.time.Duration.ofSeconds(120));
        assertThat(props.getValidate().getMaxNodes()).isEqualTo(100);
        assertThat(props.getExecution().getUnitTimeout().toSeconds()).isEqualTo(120);
    }
}