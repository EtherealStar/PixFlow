package com.pixflow.harness.hooks.error;

public record HookError(
        String callback,
        String category,
        String safeMessage) {
}
