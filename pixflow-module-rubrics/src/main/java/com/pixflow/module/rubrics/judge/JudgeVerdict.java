package com.pixflow.module.rubrics.judge;

import com.pixflow.module.rubrics.model.Confidence;
import com.pixflow.module.rubrics.model.EvidenceRef;
import com.pixflow.module.rubrics.model.Verdict;
import java.util.List;

public record JudgeVerdict(
        Verdict verdict,
        Confidence confidence,
        String rationale,
        List<EvidenceRef> evidence) {

    public JudgeVerdict {
        if (verdict == null) {
            throw new IllegalArgumentException("verdict must not be null");
        }
        confidence = confidence == null ? Confidence.MEDIUM : confidence;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
