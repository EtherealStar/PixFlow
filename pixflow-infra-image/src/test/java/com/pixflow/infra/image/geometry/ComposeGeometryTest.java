package com.pixflow.infra.image.geometry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.image.op.ComposeSpec;
import java.awt.Color;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComposeGeometryTest {
    private static final List<RasterDimensions> MEMBERS = List.of(
            new RasterDimensions(4, 3),
            new RasterDimensions(2, 5),
            new RasterDimensions(3, 2));

    @Test
    void includesVerticalGaps() {
        RasterDimensions result = ComposeGeometry.resolve(
                MEMBERS,
                spec(ComposeSpec.Layout.VERTICAL, 2));

        assertThat(result).isEqualTo(new RasterDimensions(4, 14));
    }

    @Test
    void includesGridRowsColumnsAndGaps() {
        RasterDimensions result = ComposeGeometry.resolve(
                MEMBERS,
                spec(ComposeSpec.Layout.GRID, 2));

        assertThat(result).isEqualTo(new RasterDimensions(10, 12));
    }

    @Test
    void rejectsOverflowInsteadOfWrappingDimensions() {
        assertThatThrownBy(() -> ComposeGeometry.resolve(
                List.of(new RasterDimensions(Long.MAX_VALUE, 1), new RasterDimensions(1, 1)),
                spec(ComposeSpec.Layout.HORIZONTAL, 0)))
                .isInstanceOf(ArithmeticException.class);
    }

    private static ComposeSpec spec(ComposeSpec.Layout layout, int gap) {
        return new ComposeSpec(layout, List.of(), gap, Color.WHITE);
    }
}
