package com.pixflow.module.vision.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.module.vision.api.CommonVisualFacts;
import com.pixflow.module.vision.api.ProductVisualFacts;
import com.pixflow.module.vision.api.VisualAttribute;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductVisualFactsNormalizerTest {
    private final ProductVisualFactsNormalizer normalizer =
            new ProductVisualFactsNormalizer(new ObjectMapper());

    @Test
    void trimsDeduplicatesAndKeepsFirstOccurrenceOrder() {
        ProductVisualFacts normalized = normalizer.normalize(new ProductVisualFacts(
                new CommonVisualFacts(
                        " handbag ",
                        List.of(" black ", "", "black", "gold"),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        " white ",
                        List.of()),
                List.of(new VisualAttribute(" closure ", " zipper ")),
                List.of(" single view ", "single view"),
                List.of()));

        assertThat(normalized.common().categoryAppearance()).isEqualTo("handbag");
        assertThat(normalized.common().dominantColors()).containsExactly("black", "gold");
        assertThat(normalized.attributes()).containsExactly(new VisualAttribute("closure", "zipper"));
        assertThat(normalized.limitations()).containsExactly("single view");
    }

    @Test
    void acceptsCompletelyEmptyButStructurallyCompleteDocument() {
        ProductVisualFacts empty = emptyFacts();

        assertThat(normalizer.read(normalizer.write(empty))).isEqualTo(empty);
    }

    @Test
    void rejectsUnknownFieldsAndNestedAttributeShape() {
        String unknown = """
                {
                  "common": {
                    "categoryAppearance": null,
                    "dominantColors": [], "visibleMaterials": [], "shapes": [],
                    "visibleComponents": [], "patterns": [], "visibleText": [],
                    "background": null, "viewTypes": [], "invented": true
                  },
                  "attributes": [], "limitations": [], "conflicts": []
                }
                """;
        String nested = unknown.replace(
                "\"attributes\": []",
                "\"attributes\": [{\"name\":\"x\",\"value\":{\"nested\":true}}]")
                .replace(", \"invented\": true", "");

        assertThatThrownBy(() -> normalizer.read(unknown))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> normalizer.read(nested))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsValuesBeyondContractBounds() {
        ProductVisualFacts oversized = new ProductVisualFacts(
                new CommonVisualFacts(
                        "x".repeat(201),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        List.of()),
                List.of(),
                List.of(),
                List.of());

        assertThatThrownBy(() -> normalizer.normalize(oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("categoryAppearance");
    }

    private ProductVisualFacts emptyFacts() {
        return new ProductVisualFacts(
                new CommonVisualFacts(
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        List.of()),
                List.of(),
                List.of(),
                List.of());
    }
}
