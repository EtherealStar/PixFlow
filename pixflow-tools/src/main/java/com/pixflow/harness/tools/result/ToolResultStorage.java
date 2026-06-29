package com.pixflow.harness.tools.result;

import com.pixflow.infra.storage.ObjectRef;
import java.io.InputStream;

public interface ToolResultStorage {
    StoredToolResult store(String toolCallId, String toolName, String content);

    record StoredToolResult(String ref, ObjectRef objectRef, String preview) {
        public StoredToolResult {
            ref = ref == null ? "" : ref;
            preview = preview == null ? "" : preview;
        }
    }
}
