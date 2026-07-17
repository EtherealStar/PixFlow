package com.pixflow.infra.image.geometry;

import com.pixflow.infra.image.op.ComposeSpec;
import java.util.List;
import java.util.Objects;

public final class ComposeGeometry {
    private ComposeGeometry() {
    }

    public static RasterDimensions resolve(List<RasterDimensions> members, ComposeSpec spec) {
        Objects.requireNonNull(members, "members must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        if (members.isEmpty()) {
            throw new IllegalArgumentException("members must not be empty");
        }
        return switch (spec.layout()) {
            case HORIZONTAL -> new RasterDimensions(
                    Math.addExact(sumWidths(members), gaps(spec.gap(), members.size())),
                    members.stream().mapToLong(RasterDimensions::height).max().orElseThrow());
            case VERTICAL -> new RasterDimensions(
                    members.stream().mapToLong(RasterDimensions::width).max().orElseThrow(),
                    Math.addExact(sumHeights(members), gaps(spec.gap(), members.size())));
            case GRID -> grid(members, spec.gap());
        };
    }

    private static RasterDimensions grid(List<RasterDimensions> members, int gap) {
        int columns = (int) Math.ceil(Math.sqrt(members.size()));
        int rows = (int) Math.ceil(members.size() / (double) columns);
        long cellWidth = members.stream().mapToLong(RasterDimensions::width).max().orElseThrow();
        long cellHeight = members.stream().mapToLong(RasterDimensions::height).max().orElseThrow();
        long width = Math.addExact(Math.multiplyExact(columns, cellWidth), gaps(gap, columns));
        long height = Math.addExact(Math.multiplyExact(rows, cellHeight), gaps(gap, rows));
        return new RasterDimensions(width, height);
    }

    private static long sumWidths(List<RasterDimensions> members) {
        return members.stream().mapToLong(RasterDimensions::width).reduce(0L, Math::addExact);
    }

    private static long sumHeights(List<RasterDimensions> members) {
        return members.stream().mapToLong(RasterDimensions::height).reduce(0L, Math::addExact);
    }

    private static long gaps(int gap, int count) {
        return Math.multiplyExact((long) gap, count - 1L);
    }
}
