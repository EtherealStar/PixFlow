package com.pixflow.harness.loop;

import java.util.Map;

/**
 * 用户回合附件的 durable 引用。
 *
 * <p>loop 不解析附件字节：调用方（conversation / agent）在调用 {@code AgentLoop.stream}
 * 之前把附件解析为 durable 引用（如 {@code object://...} 或 {@code attachment://...}），
 * 由 loop 透传给 context 的 {@code MessageStore.appendAttachments}。
 *
 * <p>字段含义：
 * <ul>
 *   <li>{@code id}：附件唯一 id（一般是上游 attachmentId），</li>
 *   <li>{@code kind}：附件类别（{@code image} / {@code file} / {@code url} ...），</li>
 *   <li>{@code reference}：durable 引用字符串，</li>
 *   <li>{@code metadata}：附加元信息（mime、sizeBytes 等）。</li>
 * </ul>
 */
public record Attachment(String id, String kind, String reference, Map<String, Object> metadata) {
    public Attachment {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("attachment id must not be blank");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("attachment kind must not be blank");
        }
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("attachment reference must not be blank");
        }
        id = id.trim();
        kind = kind.trim();
        reference = reference.trim();
        if (reference.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("attachment reference must not contain control characters");
        }
        if (!reference.startsWith("object://") && !reference.startsWith("attachment://")) {
            throw new IllegalArgumentException("attachment reference must start with object:// or attachment://");
        }
        metadata = MetadataValues.immutableCopy(metadata);
    }
}
