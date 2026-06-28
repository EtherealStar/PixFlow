package com.pixflow.harness.hooks.payload;

public record RuntimeScope(boolean subagent, String subagentType) {
    public RuntimeScope {
        if (!subagent) {
            subagentType = null;
        }
    }

    public static RuntimeScope main() {
        return new RuntimeScope(false, null);
    }

    public static RuntimeScope of(String type) {
        return new RuntimeScope(true, type);
    }
}
