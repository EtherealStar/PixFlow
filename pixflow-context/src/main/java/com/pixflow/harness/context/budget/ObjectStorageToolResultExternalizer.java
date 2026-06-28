package com.pixflow.harness.context.budget;

import com.pixflow.harness.context.model.ToolResultReference;
import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageKeys;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class ObjectStorageToolResultExternalizer implements ToolResultExternalizer {
    private final ObjectStorage objectStorage;

    public ObjectStorageToolResultExternalizer(ObjectStorage objectStorage) {
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage");
    }

    @Override
    public ToolResultReference externalize(String toolCallId, String content, int previewChars) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String id = safeId(toolCallId) + "-" + sha256(bytes);
        ObjectRef ref = objectStorage.put(
                StorageKeys.toolResult(id),
                new ByteArrayInputStream(bytes),
                bytes.length,
                "text/plain; charset=utf-8");
        return new ToolResultReference(id, ref.bucket().name(), ref.key(), preview(content, previewChars), bytes.length, false);
    }

    private static String preview(String content, int previewChars) {
        if (content.length() <= previewChars) {
            return content;
        }
        return content.substring(0, Math.max(0, previewChars));
    }

    private static String safeId(String toolCallId) {
        return (toolCallId == null || toolCallId.isBlank()) ? "tool" : toolCallId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
