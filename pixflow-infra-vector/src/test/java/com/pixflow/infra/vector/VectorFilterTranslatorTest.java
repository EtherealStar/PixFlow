package com.pixflow.infra.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.qdrant.client.grpc.Common;
import java.util.List;
import org.junit.jupiter.api.Test;

class VectorFilterTranslatorTest {

    @Test
    void translatesMustShouldAndMustNot() {
        Common.Filter filter = VectorFilterTranslator.translate(VectorFilter
                .must(VectorFilter.match("category", "dress"))
                .and(VectorFilter.should(VectorFilter.matchAny("source", List.of("a", "b"))))
                .and(VectorFilter.mustNot(VectorFilter.range("score", 0.7, null))));

        assertThat(filter.getMustCount()).isEqualTo(1);
        assertThat(filter.getShouldCount()).isEqualTo(1);
        assertThat(filter.getMustNotCount()).isEqualTo(1);
        assertThat(filter.getMust(0).getField().getKey()).isEqualTo("category");
        assertThat(filter.getShould(0).getField().getMatch().getKeywords().getStringsList()).containsExactly("a", "b");
        assertThat(filter.getMustNot(0).getField().getRange().getGte()).isEqualTo(0.7);
    }

    @Test
    void returnsNullForEmptyFilter() {
        assertThat(VectorFilterTranslator.translate(VectorFilter.none())).isNull();
    }

    @Test
    void rejectsMixedMatchAnyTypes() {
        VectorFilter filter = VectorFilter.must(VectorFilter.matchAny("field", List.of("a", 1L)));

        assertThatThrownBy(() -> VectorFilterTranslator.translate(filter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("homogeneous");
    }

    @Test
    void rejectsNullMatchAnyValuesAtConstruction() {
        assertThatThrownBy(() -> VectorFilter.matchAny("field", java.util.Arrays.asList("a", null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("must not contain null");
    }

    @Test
    void rejectsInvalidRangesAtConstruction() {
        assertThatThrownBy(() -> VectorFilter.range("score", Double.NaN, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
        assertThatThrownBy(() -> VectorFilter.range("score", 2.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not exceed");
    }

    @Test
    void rejectsFractionalNumericMatchValues() {
        assertThatThrownBy(() -> VectorFilterTranslator.translate(
                VectorFilter.must(VectorFilter.match("score", 1.5d))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("integral");
        assertThatThrownBy(() -> VectorFilterTranslator.translate(
                VectorFilter.must(VectorFilter.matchAny("score", List.of(1L, 2.5d)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("integral");
    }
}
