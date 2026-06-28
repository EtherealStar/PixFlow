package com.pixflow.harness.eval.support;

import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.harness.eval.model.TraceExternalPayloadRef;
import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageKeys;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class ObjectStorageTraceExternalPayloadStorage implements TraceExternalPayloadStorage {
    private final ObjectStorage objectStorage;

    public ObjectStorageTraceExternalPayloadStorage(ObjectStorage objectStorage) {
        this.objectStorage = objectStorage;
    }

    @Override
    public TraceExternalPayloadRef put(String payload) {
        String safePayload = Sanitizer.sanitizeTraceText(payload);
        byte[] bytes = safePayload.getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256(bytes);
        String id = "trace-" + sha256.substring(0, 16) + "-" + UUID.randomUUID();
        ObjectRef ref = objectStorage.put(StorageKeys.toolResult(id), new ByteArrayInputStream(bytes), bytes.length, "application/json");
        return new TraceExternalPayloadRef(ref.key(), ref.size(), ref.etag(), sha256, preview(safePayload), false);
    }

    @Override
    public String get(TraceExternalPayloadRef ref) {
        byte[] bytes = objectStorage.getBytes(StorageKeys.toolResult(ref.key().replaceFirst("\\.txt$", "")));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void delete(TraceExternalPayloadRef ref) {
        objectStorage.delete(StorageKeys.toolResult(ref.key().replaceFirst("\\.txt$", "")));
    }

    private static String preview(String payload) {
        return Sanitizer.truncate(payload, 1000);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
