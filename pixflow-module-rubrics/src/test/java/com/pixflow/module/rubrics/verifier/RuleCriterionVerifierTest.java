package com.pixflow.module.rubrics.verifier;

import static org.assertj.core.api.Assertions.assertThat;
import com.pixflow.module.rubrics.evidence.EvidenceEntry;
import com.pixflow.module.rubrics.model.CriterionKind;
import com.pixflow.module.rubrics.model.CriterionVerdict;
import com.pixflow.module.rubrics.model.EvidenceType;
import com.pixflow.module.rubrics.template.Applicability;
import com.pixflow.module.rubrics.template.Criterion;
import com.pixflow.module.rubrics.template.VerifierSpec;
import com.pixflow.module.rubrics.template.VerifierType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuleCriterionVerifierTest {
    private final RuleCriterionVerifier verifier = new RuleCriterionVerifier();

    @Test void resolutionBoundaryIsDeterministic() {
        Criterion criterion = criterion("resolution", Map.of("minWidth", 800, "minHeight", 800));
        assertThat(verifier.verify(criterion, List.of(metadata(799, 800, "PNG"))).verdict())
                .isEqualTo(CriterionVerdict.FAIL);
        assertThat(verifier.verify(criterion, List.of(metadata(800, 800, "PNG"))).verdict())
                .isEqualTo(CriterionVerdict.PASS);
    }

    @Test void unavailableMetadataIsInconclusiveRatherThanFail() {
        CriterionResult result = verifier.verify(criterion("resolution", Map.of("minWidth", 800, "minHeight", 800)), List.of());
        assertThat(result.verdict()).isEqualTo(CriterionVerdict.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo(VerdictReason.MISSING_EVIDENCE);
    }

    private Criterion criterion(String rule, Map<String, Object> params) {
        return new Criterion(rule, CriterionKind.HARD_RULE, "statement", "pass", "fail",
                Set.of(EvidenceType.IMAGE_METADATA), Applicability.ALWAYS,
                new VerifierSpec(VerifierType.RULE, rule, null, params));
    }

    private EvidenceEntry metadata(int width, int height, String format) {
        return new EvidenceEntry("E2", EvidenceType.IMAGE_METADATA, "results/x", "hash", java.time.Instant.EPOCH,
                Map.of("width", width, "height", height, "format", format));
    }
}
