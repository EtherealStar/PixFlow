package com.pixflow.module.dag.ir;

import java.util.Objects;

/**
 * DAG 参数 schema 的版本标签:用于 schema 演进约束(加法兼容 + 大版本升级,见 dag.md §6.2.1)。
 *
 * <p>同一 tool 资源文件 version 字段累加；大版本变化后旧 ephemeral Proposal 不可恢复。
 * 当前 dag 加载的所有 schema 大版本号在 {@link com.pixflow.module.dag.validate.SchemaRegistryValidator}
 * 启动期自检中唯一识别。
 */
public record DagSchemaVersion(String raw) {
    public DagSchemaVersion {
        Objects.requireNonNull(raw, "raw");
        if (raw.isBlank()) {
            throw new IllegalArgumentException("schema version must not be blank");
        }
    }

    /**
     * 取大版本号:形如 "1.2" → 1,"2.0" → 2;解析失败抛 IllegalArgumentException。
     */
    public int major() {
        int dot = raw.indexOf('.');
        String majorPart = dot < 0 ? raw : raw.substring(0, dot);
        try {
            return Integer.parseInt(majorPart);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid schema version: " + raw, e);
        }
    }
}
