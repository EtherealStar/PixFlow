package com.pixflow.harness.tools.result;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class DefaultToolResultStorage implements ToolResultStorage {
    private final ObjectStorage objectStorage;

    public DefaultToolResultStorage(ObjectStorage objectStorage) {
        this.objectStorage = Objects.requireNonNull(objectStorage, "objectStorage");
    }

    @Override
    public StoredToolResult store(String toolCallId, String toolName, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String key = "tool-results/" + toolName + "/" + toolCallId + ".txt";
        ObjectLocation location = ObjectLocation.of(BucketType.TOOL_RESULTS, key);
        ObjectRef ref = objectStorage.put(location, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
        return new StoredToolResult("tool-results://" + ref.bucket().name().toLowerCase() + "/" + ref.key(), ref, content);
    }
}
