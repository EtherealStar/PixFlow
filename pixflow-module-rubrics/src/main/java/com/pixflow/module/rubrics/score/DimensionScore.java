package com.pixflow.module.rubrics.score;

import com.pixflow.module.rubrics.model.Confidence;
import com.pixflow.module.rubrics.model.EvidenceRef;
import com.pixflow.module.rubrics.model.Verdict;
import java.math.BigDecimal;
import java.util.List;

public record DimensionScore(
        String domainKey,
        String dimensionKey,
        Verdict verdict,
        Confidence confidence,
        BigDecimal score,
        String rationale,
        List<EvidenceRef> evidence,
        Double numericMetric) {

    public DimensionScore {
        if (domainKey == null || domainKey.isBlank()) {
            throw new IllegalArgumentException("domainKey must not be blank");
        }
        if (dimensionKey == null || dimensionKey.isBlank()) {
            throw new IllegalArgumentException("dimensionKey must not be blank");
        }
        if (verdict == null) {
            throw new IllegalArgumentException("verdict must not be null");
        }
        if (confidence == null) {
            confidence = Confidence.MEDIUM;
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
