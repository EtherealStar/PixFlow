package com.pixflow.module.rubrics.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.rubrics.model.SubjectType;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateLoaderTest {
    @TempDir
    Path templateDirectory;

    @Test
    void loadsValidatedImageTemplateWithCanonicalHash() {
        TemplateLoader loader = new TemplateLoader(new TemplateValidator(new ObjectMapper()));

        LoadedTemplate loaded = loader.load("rubrics/templates/", "").getFirst();

        assertThat(loaded.template().id()).isEqualTo("image-result-quality");
        assertThat(loaded.template().subjectType()).isEqualTo(SubjectType.IMAGE_RESULT);
        assertThat(loaded.canonicalHash()).hasSize(64);
    }

    @Test
    void semanticVersionsSortNumericallyAndRejectInvalidSyntax() {
        assertThat(SemanticVersion.parse("2.10.0"))
                .isGreaterThan(SemanticVersion.parse("2.9.0"));
        assertThatThrownBy(() -> SemanticVersion.parse("2.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownYamlFields() throws Exception {
        Files.writeString(templateDirectory.resolve("unknown.yaml"), validTemplate()
                + System.lineSeparator() + "unexpected: true" + System.lineSeparator());
        TemplateLoader loader = new TemplateLoader(new TemplateValidator(new ObjectMapper()));

        assertThatThrownBy(() -> loader.load("missing-rubrics/", templateDirectory.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown.yaml");
    }

    @Test
    void rejectsDuplicateYamlKeys() throws Exception {
        Files.writeString(templateDirectory.resolve("duplicate.yaml"), validTemplate()
                .replace("version: 1.0.0", "version: 1.0.0\nversion: 2.0.0"));
        TemplateLoader loader = new TemplateLoader(new TemplateValidator(new ObjectMapper()));

        assertThatThrownBy(() -> loader.load("missing-rubrics/", templateDirectory.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate.yaml");
    }

    private static String validTemplate() {
        return """
                id: strict-template
                version: 1.0.0
                name: Strict Template
                subjectType: IMAGE_RESULT
                lifecycle: EXPERIMENTAL
                evaluator:
                  judgeRole: RUBRICS_JUDGE_VISION
                  rollouts: 3
                  parserSchemaVersion: "1"
                criteria:
                  - key: resolution
                    kind: HARD_RULE
                    statement: The output has the required resolution.
                    passAnchor: Width and height meet the configured minimum.
                    failAnchor: Width or height is below the configured minimum.
                    evidenceTypes: [IMAGE_METADATA]
                    applicability: ALWAYS
                    verifier:
                      type: RULE
                      ruleClass: resolution
                      params: { minWidth: 800, minHeight: 800 }
                """;
    }
}
