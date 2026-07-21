package com.pixflow.app.activity;

import java.util.Objects;

/** Activity 命令的服务端目标；source 信息只保存在 App 内部，不进入 wire DTO。 */
public record ActivityCommandTarget(
        ActivitySourceKind sourceKind,
        String sourceId,
        ActivityView view) {
    public ActivityCommandTarget {
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(view, "view");
    }
}
