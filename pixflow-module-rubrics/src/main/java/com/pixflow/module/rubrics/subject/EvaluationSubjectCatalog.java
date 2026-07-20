package com.pixflow.module.rubrics.subject;

import com.pixflow.module.rubrics.model.SubjectType;

/** 集中解析三类 owner snapshot，运行引擎不感知具体 adapter。 */
public final class EvaluationSubjectCatalog {
    private final ImageSubjectSnapshotResolver images;

    private final TextSubjectSnapshotResolver texts;

    public EvaluationSubjectCatalog(
            ImageSubjectSnapshotResolver images, TextSubjectSnapshotResolver texts) {
        this.images = images;
        this.texts = texts;
    }

    public EvaluationSubject resolve(SubjectType type, String subjectId) {
        return switch (type) {
            case IMAGE_RESULT -> images.resolve(subjectId);
            case COPY_RESULT -> texts.resolveCopy(subjectId);
            case TASK_DECISION -> texts.resolveDecision(subjectId);
        };
    }
}
