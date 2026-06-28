package com.pixflow.harness.hooks.payload;

public record RuntimeScope(boolean subagent, String subagentType) {
    public RuntimeScope {
        if (subagent && subagentType == null) {
            throw new IllegalArgumentException("subagentType must not be null when subagent is true");
        }
        if (!subagent) {
            subagentType = null;
        }
    }

    public static RuntimeScope main() {
        return new RuntimeScope(false, null);
    }

    public static RuntimeScope of(String type) {
        if (type == null) {
            throw new IllegalArgumentException("subagentType must not be null");
        }
        return new RuntimeScope(true, type);
    }
}
