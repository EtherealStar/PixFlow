package com.pixflow.harness.hooks.internal;

import com.pixflow.harness.hooks.HookEvent;
import com.pixflow.harness.hooks.HookResult;
import com.pixflow.harness.hooks.error.HookError;
import com.pixflow.harness.hooks.payload.HookPayload;
import com.pixflow.harness.hooks.payload.ToolUsePayload;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DispatchAccumulator {
    private final HookEvent event;

    private final HookPayload originalPayload;

    private HookPayload currentPayload;

    private String blockingReason;

    private Map<String, Object> updatedInput = new LinkedHashMap<>();

    private Map<String, Object> metadata = new LinkedHashMap<>();

    public DispatchAccumulator(HookEvent event, HookPayload payload) {
        this.event = event;
        this.originalPayload = payload;
        this.currentPayload = payload;
    }

    public HookPayload currentPayload() {
        return currentPayload;
    }

    public void accept(HookResult result) {
        metadata = MetadataMerger.merge(metadata, result.metadata());
        if (event == HookEvent.PRE_TOOL_USE
                && currentPayload instanceof ToolUsePayload toolPayload
                && result.inputRewritten()) {
            // updatedInput 是顶层浅 patch；这里不做嵌套合并，避免总线理解具体工具 schema。
            Map<String, Object> patched = new LinkedHashMap<>(toolPayload.toolInput());
            patched.putAll(result.updatedInput());
            currentPayload = toolPayload.withToolInput(patched);
            updatedInput.putAll(result.updatedInput());
        } else if (result.inputRewritten()) {
            updatedInput.putAll(result.updatedInput());
        }
        if (result.blocked()) {
            blockingReason = result.blockingReason();
        }
    }

    public void appendHookError(HookError error) {
        metadata = MetadataMerger.appendHookError(metadata, error);
    }

    public HookResult toResult() {
        return new HookResult(blockingReason, updatedInput, metadata);
    }

    HookPayload originalPayload() {
        return originalPayload;
    }
}
