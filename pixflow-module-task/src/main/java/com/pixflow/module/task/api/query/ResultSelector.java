package com.pixflow.module.task.api.query;

public record ResultSelector(Long resultId, String imageId, String groupKey, String branchId, boolean bundle) {
    public static ResultSelector byResultId(long resultId) {
        return new ResultSelector(resultId, null, null, null, false);
    }

    public static ResultSelector byBranch(String imageId, String branchId) {
        return new ResultSelector(null, imageId, null, branchId, false);
    }

    public static ResultSelector allResultsBundle() {
        return new ResultSelector(null, null, null, null, true);
    }
}
