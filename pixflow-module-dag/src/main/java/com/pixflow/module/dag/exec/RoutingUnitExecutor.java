package com.pixflow.module.dag.exec;

import com.pixflow.module.dag.expand.ExecutableBranch;
import java.util.Objects;

/** 按编译期 binding 把类型化支路路由到唯一真实执行器。 */
public final class RoutingUnitExecutor implements UnitExecutor {
    private final StepBindingRegistry bindings;

    private final PipelineUnitExecutor pipeline;

    private final GroupUnitExecutor group;

    private final CopyUnitExecutor copy;

    public RoutingUnitExecutor(StepBindingRegistry bindings, PipelineUnitExecutor pipeline,
                               GroupUnitExecutor group, CopyUnitExecutor copy) {
        this.bindings = Objects.requireNonNull(bindings);
        this.pipeline = Objects.requireNonNull(pipeline);
        this.group = Objects.requireNonNull(group);
        this.copy = Objects.requireNonNull(copy);
    }

    @Override
    public UnitOutcome execute(ExecutableBranch branch, UnitInput input) {
        Objects.requireNonNull(branch, "branch");
        if (branch.composeStep() != null) {
            require(branch.composeStep(), StepBindingRegistry.ExecutorBinding.GROUP);
            branch.perMemberOps().forEach(step -> require(step,
                    StepBindingRegistry.ExecutorBinding.PIPELINE));
            branch.postOps().forEach(step -> require(step,
                    StepBindingRegistry.ExecutorBinding.PIPELINE));
            return group.execute(branch, input);
        }
        boolean copyBranch = branch.perMemberOps().stream()
                .anyMatch(step -> bindings.requireExecutor(step.tool())
                        == StepBindingRegistry.ExecutorBinding.COPY);
        StepBindingRegistry.ExecutorBinding expected = copyBranch
                ? StepBindingRegistry.ExecutorBinding.COPY
                : StepBindingRegistry.ExecutorBinding.PIPELINE;
        branch.perMemberOps().forEach(step -> require(step, expected));
        branch.postOps().forEach(step -> require(step, expected));
        return copyBranch ? copy.execute(branch, input) : pipeline.execute(branch, input);
    }

    private void require(ExecutionStep step, StepBindingRegistry.ExecutorBinding expected) {
        var actual = bindings.requireExecutor(step.tool());
        if (actual != expected) {
            throw new IllegalStateException(step.nodeId() + " 的 executor binding 应为 "
                    + expected + "，实际为 " + actual);
        }
    }
}
