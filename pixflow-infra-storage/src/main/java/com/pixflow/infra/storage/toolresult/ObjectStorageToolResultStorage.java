package com.pixflow.infra.storage.toolresult;

import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageKeys;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class ObjectStorageToolResultStorage implements ToolResultStorage {
    private static final String CONTENT_TYPE = "text/plain; charset=utf-8";

    private final ObjectStorage objectStorage;

    public ObjectStorageToolResultStorage(ObjectStorage objectStorage) {
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage");
    }

    @Override
    public StoredToolResultReference write(String toolCallId, String content, int previewChars) {
        String safeContent = content == null ? "" : content;
        byte[] bytes = safeContent.getBytes(StandardCharsets.UTF_8);
        String id = safeId(toolCallId) + "-" + sha256(bytes);
        ObjectRef ref = objectStorage.put(
                StorageKeys.toolResult(id),
                new ByteArrayInputStream(bytes),
                bytes.length,
                CONTENT_TYPE);
        return new StoredToolResultReference(
                id,
                ref.bucket().name(),
                ref.key(),
                preview(safeContent, previewChars),
                bytes.length,
                false);
    }

    @Override
    public StoredToolResultContent read(StoredToolResultReference reference) {
        Objects.requireNonNull(reference, "reference");
        var location = StorageKeys.toolResult(reference.id());
        if (!objectStorage.exists(location)) {
            return new StoredToolResultContent(reference.preview(), reference.asMissing());
        }
        byte[] bytes = objectStorage.getBytes(location);
        return new StoredToolResultContent(
                new String(bytes, StandardCharsets.UTF_8),
                new StoredToolResultReference(
                        reference.id(),
                        location.bucket().name(),
                        location.key(),
                        reference.preview(),
                        reference.originalBytes(),
                        false));
    }

    private static String preview(String content, int previewChars) {
        int length = Math.max(0, previewChars);
        return content.length() <= length ? content : content.substring(0, length);
    }

    private static String safeId(String toolCallId) {
        return (toolCallId == null || toolCallId.isBlank())
                ? "tool"
                : toolCallId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 24);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
