package com.pixflow.harness.hooks;

import com.pixflow.harness.hooks.payload.HookPayload;
import java.util.Set;

public interface HookCallback {
    Set<HookEvent> supportedEvents();

    default int order() {
        return 0;
    }

    HookResult handle(HookEvent event, HookPayload payload);
}
