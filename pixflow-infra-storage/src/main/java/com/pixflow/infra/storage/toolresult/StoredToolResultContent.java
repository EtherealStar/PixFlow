package com.pixflow.infra.storage.toolresult;

public record StoredToolResultContent(String content, StoredToolResultReference reference) {
    public StoredToolResultContent {
        content = content == null ? "" : content;
    }

    public boolean missing() {
        return reference != null && reference.missing();
    }
}
