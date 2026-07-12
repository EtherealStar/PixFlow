package com.pixflow.module.rubrics.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.rubrics.model.SubjectType;
import org.junit.jupiter.api.Test;

class TemplateLoaderTest {
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
}
