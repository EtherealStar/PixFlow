package com.pixflow.module.rubrics.subject;

import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.rubrics.model.SubjectType;

public record ImageResultSubject(String id, long taskId, String skuId, String unitKind,
                                 String imageId, String groupKey, String viewId, String branchId,
                                 long bytesOut, ObjectLocation output, String snapshotHash) implements EvaluationSubject {
    @Override public SubjectType type() { return SubjectType.IMAGE_RESULT; }
}
