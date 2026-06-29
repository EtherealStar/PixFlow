package com.pixflow.harness.tools;

public record ToolResultPolicy(int maxResultSizeChars, boolean persistWhenExceeded, int previewChars) {
    public ToolResultPolicy {
        if (maxResultSizeChars < 1) {
            throw new IllegalArgumentException("maxResultSizeChars 必须大于 0");
        }
        if (previewChars < 0) {
            throw new IllegalArgumentException("previewChars 不能小于 0");
        }
    }

    public static ToolResultPolicy defaults() {
        return new ToolResultPolicy(50_000, true, 4_000);
    }
}
