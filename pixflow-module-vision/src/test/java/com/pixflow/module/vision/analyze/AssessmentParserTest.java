package com.pixflow.module.vision.analyze;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AssessmentParserTest {
    private final AssessmentParser parser = new AssessmentParser(new ObjectMapper());

    @Test
    void parsesJsonCodeFence() {
        AssessmentParser.ParseOutcome outcome = parser.parse("""
                ```json
                {"composition":"front product","backgroundClean":true,"hasWatermark":false,
                 "matchesDescription":true,"sellingPoints":["cotton","blue"],"issues":[],"confidence":0.86}
                ```
                """);

        assertThat(outcome.degraded()).isFalse();
        assertThat(outcome.assessment().composition()).isEqualTo("front product");
        assertThat(outcome.assessment().backgroundClean()).isTrue();
        assertThat(outcome.assessment().sellingPoints()).containsExactly("cotton", "blue");
        assertThat(outcome.assessment().confidence()).isEqualTo(0.86d);
    }

    @Test
    void missingFieldsAreNullable() {
        AssessmentParser.ParseOutcome outcome = parser.parse("{\"composition\":\"top view\"}");

        assertThat(outcome.degraded()).isFalse();
        assertThat(outcome.assessment().composition()).isEqualTo("top view");
        assertThat(outcome.assessment().matchesDescription()).isNull();
        assertThat(outcome.assessment().sellingPoints()).isEmpty();
    }

    @Test
    void invalidJsonDegradesToRawText() {
        AssessmentParser.ParseOutcome outcome = parser.parse("free text only");

        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.assessment().rawText()).isEqualTo("free text only");
        assertThat(outcome.assessment().confidence()).isLessThanOrEqualTo(0.3d);
    }
}
