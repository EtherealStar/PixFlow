package com.pixflow.module.rubrics.evidence;

import com.pixflow.module.rubrics.subject.CopyResultSubject;
import com.pixflow.module.rubrics.subject.EvaluationSubject;
import com.pixflow.module.rubrics.subject.ImageResultSubject;
import com.pixflow.module.rubrics.subject.TaskDecisionSubject;

/** 集中构造类型化 Evidence Pack，避免运行引擎复制 Subject 分派。 */
public final class EvidencePackFactory {
    private final ImageEvidencePackBuilder images;

    private final TextEvidencePackBuilder texts;

    public EvidencePackFactory(ImageEvidencePackBuilder images, TextEvidencePackBuilder texts) {
        this.images = images;
        this.texts = texts;
    }

    public EvidencePack build(EvaluationSubject subject) {
        return switch (subject) {
            case ImageResultSubject image -> images.build(image);
            case CopyResultSubject copy -> texts.build(copy);
            case TaskDecisionSubject decision -> texts.build(decision);
            default -> throw new IllegalArgumentException(
                    "unsupported evaluation subject: " + subject.type());
        };
    }
}
