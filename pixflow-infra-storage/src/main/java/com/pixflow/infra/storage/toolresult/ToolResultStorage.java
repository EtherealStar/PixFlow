package com.pixflow.infra.storage.toolresult;

public interface ToolResultStorage {
    StoredToolResultReference write(String toolCallId, String content, int previewChars);

    StoredToolResultContent read(StoredToolResultReference reference);
}
