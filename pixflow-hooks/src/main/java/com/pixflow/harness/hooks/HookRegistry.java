package com.pixflow.harness.hooks;

import com.pixflow.harness.hooks.payload.HookPayload;

public interface HookRegistry {
    HookResult dispatch(HookEvent event, HookPayload payload);
}
