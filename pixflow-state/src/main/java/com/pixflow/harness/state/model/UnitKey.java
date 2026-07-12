package com.pixflow.harness.state.model;

import java.util.Objects;

public record UnitKey(String taskId, UnitKind kind, String memberId, String branchId) {

    public UnitKey {
        taskId = requireText(taskId, "taskId");
        kind = Objects.requireNonNull(kind, "kind");
        memberId = requireText(memberId, "memberId");
        branchId = requireText(branchId, "branchId");
    }

    public static UnitKey branch(String taskId, String imageId, String branchId) {
        return new UnitKey(taskId, UnitKind.BRANCH, imageId, branchId);
    }

    public static UnitKey group(String taskId, String groupKey, String branchId) {
        return new UnitKey(taskId, UnitKind.GROUP, groupKey, branchId);
    }

    public static UnitKey generative(String taskId, String sourceImageId) {
        return new UnitKey(taskId, UnitKind.GENERATIVE, sourceImageId, "GENERATIVE");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
