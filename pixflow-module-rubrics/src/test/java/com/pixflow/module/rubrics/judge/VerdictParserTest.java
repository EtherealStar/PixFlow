package com.pixflow.module.rubrics.judge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.rubrics.model.Confidence;
import com.pixflow.module.rubrics.model.EvidenceType;
import com.pixflow.module.rubrics.model.Verdict;
import org.junit.jupiter.api.Test;

class VerdictParserTest {
    @Test
    void parsesJsonInsideMarkdownFenceAndIgnoresNumericNoise() {
        VerdictParser parser = new VerdictParser(new ObjectMapper());

        JudgeVerdict verdict = parser.parse("""
                ```json
                {"verdict":"PASS","confidence":"HIGH","score":97,"rationale":"clean","evidence":[{"type":"IMAGE","ref":"k","excerpt":"background clean"}]}
                ```
                """);

        assertThat(verdict.verdict()).isEqualTo(Verdict.PASS);
        assertThat(verdict.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(verdict.evidence()).hasSize(1);
        assertThat(verdict.evidence().get(0).type()).isEqualTo(EvidenceType.IMAGE);
    }
}
