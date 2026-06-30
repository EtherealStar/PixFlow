package com.pixflow.harness.loop;

import com.pixflow.harness.hooks.HookEvent;
import com.pixflow.harness.hooks.HookRegistry;
import com.pixflow.harness.hooks.HookResult;
import com.pixflow.harness.hooks.payload.HookPayload;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试用 HookRegistry：记录派发顺序，返回 noop HookResult。
 */
public final class FakeHookRegistry implements HookRegistry {

    private final List<Dispatch> dispatched = Collections.synchronizedList(new ArrayList<>());

    @Override
    public HookResult dispatch(HookEvent event, HookPayload payload) {
        dispatched.add(new Dispatch(event, payload));
        return HookResult.noop();
    }

    public List<Dispatch> dispatched() {
        synchronized (dispatched) {
            return new ArrayList<>(dispatched);
        }
    }

    public List<Dispatch> dispatchedOfType(HookEvent event) {
        return dispatched().stream().filter(d -> d.event == event).toList();
    }

    public record Dispatch(HookEvent event, HookPayload payload) {
    }
}