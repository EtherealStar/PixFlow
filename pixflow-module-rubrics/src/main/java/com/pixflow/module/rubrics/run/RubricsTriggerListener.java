package com.pixflow.module.rubrics.run;

import com.pixflow.harness.hooks.HookCallback;
import com.pixflow.harness.hooks.HookEvent;
import com.pixflow.harness.hooks.HookResult;
import com.pixflow.harness.hooks.payload.HookPayload;
import com.pixflow.harness.hooks.payload.TaskLifecyclePayload;
import com.pixflow.module.rubrics.config.RubricsProperties;
import java.util.Set;
import java.util.concurrent.Executor;

public class RubricsTriggerListener implements HookCallback {
    private final EvaluationRunner runner;
    private final RubricsProperties properties;
    private final Executor executor;

    public RubricsTriggerListener(EvaluationRunner runner, RubricsProperties properties, Executor executor) {
        this.runner = runner;
        this.properties = properties;
        this.executor = executor;
    }

    @Override
    public Set<HookEvent> supportedEvents() {
        return Set.of(HookEvent.TASK_COMPLETED);
    }

    @Override
    public int order() {
        return 1000;
    }

    @Override
    public HookResult handle(HookEvent event, HookPayload payload) {
        if (!properties.getEventTrigger().isEnabled() || !(payload instanceof TaskLifecyclePayload taskPayload)) {
            return HookResult.noop();
        }
        try {
            long taskId = Long.parseLong(taskPayload.taskId());
            executor.execute(() -> runner.startForTask(taskId));
        } catch (RuntimeException ignored) {
            return HookResult.noop();
        }
        return HookResult.noop();
    }
}
