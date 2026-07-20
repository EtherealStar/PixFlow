package com.pixflow.module.rubrics.api;

import com.pixflow.module.rubrics.evidence.EvidenceEntry;
import com.pixflow.module.rubrics.model.QualityGate;
import com.pixflow.module.rubrics.model.SubjectType;
import java.util.List;

public record EvaluationView(long id, long runId, SubjectType subjectType, String subjectId,
                             String subjectSnapshotHash, String templateId, String templateVersion,
                             String templateHash, String evaluatorVersion, String evidencePackHash,
                             List<EvidenceEntry> evidence, QualityGate qualityGate, Double passRate,
                             Double coverage, boolean selfJudged, List<CriterionResultView> criteria) {
    public EvaluationView {
        evidence = List.copyOf(evidence);
        criteria = List.copyOf(criteria);
    }

    public EvaluationView(long id, long runId, SubjectType subjectType, String subjectId,
                          String subjectSnapshotHash, String templateId, String templateVersion,
                          String templateHash, String evaluatorVersion, String evidencePackHash,
                          List<EvidenceEntry> evidence, QualityGate qualityGate, Double passRate,
                          Double coverage, List<CriterionResultView> criteria) {
        this(id, runId, subjectType, subjectId, subjectSnapshotHash, templateId, templateVersion,
                templateHash, evaluatorVersion, evidencePackHash, evidence, qualityGate, passRate,
                coverage, false, criteria);
    }
}
