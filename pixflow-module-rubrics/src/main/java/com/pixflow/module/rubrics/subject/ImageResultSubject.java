package com.pixflow.module.rubrics.subject;

import com.pixflow.module.rubrics.model.SubjectType;

public record ImageResultSubject(String id, long taskId, String skuId, String unitKind,
                                 String imageId, String groupKey, String viewId, String branchId,
                                 long generatedImageId, String referenceKey, long bytesOut,
                                 String snapshotHash) implements EvaluationSubject {
    @Override public SubjectType type() { return SubjectType.IMAGE_RESULT; }
}
