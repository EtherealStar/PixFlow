package com.pixflow.harness.session.externalize;

import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import com.pixflow.harness.context.model.MessageRole;
import com.pixflow.harness.context.model.ToolResultReference;
import com.pixflow.infra.storage.toolresult.StoredToolResultReference;
import com.pixflow.infra.storage.toolresult.ToolResultStorage;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class SessionToolResultExternalizer {
    public static final String MISSING_EXTERNAL_TOOL_RESULT = "missingExternalToolResult";

    private final ToolResultStorage toolResultStorage;

    private final long thresholdBytes;

    private final int previewChars;

    public SessionToolResultExternalizer(
            ToolResultStorage toolResultStorage,
            long thresholdBytes,
            int previewChars) {
        this.toolResultStorage = Objects.requireNonNull(toolResultStorage, "toolResultStorage");
        this.thresholdBytes = thresholdBytes;
        this.previewChars = previewChars;
    }

    public Message externalizeIfNeeded(Message message) {
        if (message.role() != MessageRole.TOOL_RESULT) {
            return message;
        }
        if (message.metadata().flag(MessageMetadata.TOOL_RESULT_EXTERNALIZED)) {
            return message;
        }
        byte[] bytes = message.content().getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= thresholdBytes) {
            return message;
        }
        StoredToolResultReference stored = toolResultStorage.write(
                message.toolCallId(), message.content(), previewChars);
        ToolResultReference ref = toContextRef(stored);
        MessageMetadata metadata = message.metadata()
                .with(MessageMetadata.TOOL_RESULT_EXTERNALIZED, true)
                .with(MessageMetadata.TOOL_RESULT_REF, ref);
        return message.withContent(referenceContent(ref)).withMetadata(metadata);
    }

    public Message rehydrate(Message message) {
        if (!message.metadata().flag(MessageMetadata.TOOL_RESULT_EXTERNALIZED)) {
            return message;
        }
        ToolResultReference ref = readReference(message.metadata().values().get(MessageMetadata.TOOL_RESULT_REF));
        if (ref == null) {
            return message.withMetadata(message.metadata().with(MISSING_EXTERNAL_TOOL_RESULT, true));
        }
        var content = toolResultStorage.read(toStoredRef(ref));
        ToolResultReference nextRef = toContextRef(content.reference());
        MessageMetadata metadata = message.metadata()
                .with(MessageMetadata.TOOL_RESULT_REF, nextRef)
                .with(MISSING_EXTERNAL_TOOL_RESULT, content.missing());
        return message.withContent(content.content()).withMetadata(metadata);
    }

    private static String referenceContent(ToolResultReference ref) {
        return "[externalized tool result: " + ref.bucket() + "/" + ref.key() + "]\n" + ref.preview();
    }

    private static ToolResultReference toContextRef(StoredToolResultReference ref) {
        return new ToolResultReference(
                ref.id(),
                ref.bucket(),
                ref.key(),
                ref.preview(),
                ref.originalBytes(),
                ref.missing());
    }

    private static StoredToolResultReference toStoredRef(ToolResultReference ref) {
        return new StoredToolResultReference(
                ref.id(),
                ref.bucket(),
                ref.key(),
                ref.preview(),
                ref.originalBytes(),
                ref.missing());
    }

    @SuppressWarnings("unchecked")
    private static ToolResultReference readReference(Object value) {
        if (value instanceof ToolResultReference ref) {
            return ref;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), item));
            Object id = copy.get("id");
            Object bucket = copy.get("bucket");
            Object key = copy.get("key");
            if (id == null || bucket == null || key == null) {
                return null;
            }
            Object preview = copy.get("preview");
            Object originalBytes = copy.get("originalBytes");
            Object missing = copy.get("missing");
            return new ToolResultReference(
                    String.valueOf(id),
                    String.valueOf(bucket),
                    String.valueOf(key),
                    preview == null ? "" : String.valueOf(preview),
                    originalBytes instanceof Number number ? number.longValue() : 0L,
                    missing instanceof Boolean bool && bool);
        }
        return null;
    }
}
