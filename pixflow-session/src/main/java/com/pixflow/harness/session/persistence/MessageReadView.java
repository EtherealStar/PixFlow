package com.pixflow.harness.session.persistence;

import java.time.Instant;

/**
 * 对 web 出口开放的 message 只读投影。
 *
 * <p>该类型只承载历史展示字段，不暴露任何写入方法，避免 conversation 误用 session 的写路径。
 */
public record MessageReadView(
        String id,
        String conversationId,
        Long seq,
        String role,
        String content,
        String toolCallId,
        String compactionMarker,
        String metadata,
        String attachedPackageId,
        String taskId,
        Instant createdAt) {
}
