package com.pixflow.module.task.domain.model;

import com.pixflow.harness.state.model.UnitKey;
import com.pixflow.module.dag.expand.ExecutableBranch;
import com.pixflow.module.dag.expand.ImageDescriptor;
import com.pixflow.module.imagegen.exec.GenerativeUnitSpec;
import java.util.List;
import java.util.Objects;

public record WorkUnit(
    String taskId,
    TaskType taskType,
    UnitKey unitKey,
    List<ImageDescriptor> imageDescriptors,
    ExecutableBranch executableBranch,
    GenerativeUnitSpec generativeSpec) {

  public WorkUnit {
    taskId = requireText(taskId, "taskId");
    taskType = Objects.requireNonNull(taskType, "taskType");
    unitKey = Objects.requireNonNull(unitKey, "unitKey");
    if (!taskId.equals(unitKey.taskId())) {
      throw new IllegalArgumentException("unitKey taskId must match taskId");
    }
    imageDescriptors = imageDescriptors == null ? List.of() : List.copyOf(imageDescriptors);
  }

  public static WorkUnit branch(
      String taskId, ExecutableBranch branch, List<ImageDescriptor> images) {
    UnitKey key =
        branch.kind() == com.pixflow.harness.state.model.UnitKind.GROUP
            ? UnitKey.group(taskId, branch.memberId(), branch.branchId())
            : UnitKey.branch(taskId, branch.memberId(), branch.branchId());
    return new WorkUnit(taskId, TaskType.IMAGE_PROCESS, key, images, branch, null);
  }

  public static WorkUnit generative(String taskId, GenerativeUnitSpec spec) {
    return new WorkUnit(
        taskId,
        TaskType.IMAGE_GEN,
        UnitKey.generative(taskId, spec.sourceImageId()),
        List.of(),
        null,
        spec);
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value.trim();
  }
}
