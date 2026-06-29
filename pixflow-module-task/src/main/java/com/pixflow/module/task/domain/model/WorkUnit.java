package com.pixflow.module.task.domain.model;

import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.module.imagegen.exec.GenerativeUnitSpec;
import java.util.List;
import java.util.Objects;

public record WorkUnit(
        String taskId,
        TaskType taskType,
        UnitKind kind,
        String memberId,
        String branchId,
        List<ImageDescriptor> imageDescriptors,
        ExecutableBranch executableBranch,
        GenerativeUnitSpec generativeSpec) {

    public WorkUnit {
        taskId = requireText(taskId, "taskId");
        taskType = Objects.requireNonNull(taskType, "taskType");
        kind = Objects.requireNonNull(kind, "kind");
        memberId = requireText(memberId, "memberId");
        branchId = requireText(branchId, "branchId");
        imageDescriptors = imageDescriptors == null ? List.of() : List.copyOf(imageDescriptors);
    }

    public static WorkUnit branch(String taskId, ExecutableBranch branch, List<ImageDescriptor> images) {
        UnitKind kind = branch.kind() == com.pixflow.harness.state.model.UnitKind.GROUP
                ? UnitKind.GROUP : UnitKind.BRANCH;
        return new WorkUnit(taskId, TaskType.IMAGE_PROCESS, kind, branch.memberId(), branch.branchId(),
                images, branch, null);
    }

    public static WorkUnit generative(String taskId, GenerativeUnitSpec spec) {
        return new WorkUnit(taskId, TaskType.IMAGE_GEN, UnitKind.GENERATIVE, spec.sourceImageId(),
                "GENERATIVE", List.of(), null, spec);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
