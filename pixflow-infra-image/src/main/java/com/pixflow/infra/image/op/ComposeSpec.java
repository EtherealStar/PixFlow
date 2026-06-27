package com.pixflow.infra.image.op;

import java.awt.Color;
import java.util.List;

public record ComposeSpec(Layout layout, List<Integer> order, int gap, Color background) {
    public enum Layout {
        HORIZONTAL,
        VERTICAL,
        GRID
    }

    public ComposeSpec {
        layout = layout == null ? Layout.GRID : layout;
        if (gap < 0) {
            throw new IllegalArgumentException("gap must not be negative");
        }
        background = background == null ? Color.WHITE : background;
        order = order == null ? List.of() : List.copyOf(order);
    }
}
