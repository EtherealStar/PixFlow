package com.pixflow.harness.context.budget;

import com.pixflow.harness.context.model.ToolResultReference;

public interface ToolResultExternalizer {
    ToolResultReference externalize(String toolCallId, String content, int previewChars);
}
