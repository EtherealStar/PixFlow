package com.pixflow.module.vision.api;

import java.util.List;
import java.util.Objects;

/**
 * 所有商品类别共享的可观察视觉事实。
 */
public record CommonVisualFacts(
        String categoryAppearance,
        List<String> dominantColors,
        List<String> visibleMaterials,
        List<String> shapes,
        List<String> visibleComponents,
        List<String> patterns,
        List<String> visibleText,
        String background,
        List<String> viewTypes) {

    public CommonVisualFacts {
        dominantColors = immutable(dominantColors, "dominantColors");
        visibleMaterials = immutable(visibleMaterials, "visibleMaterials");
        shapes = immutable(shapes, "shapes");
        visibleComponents = immutable(visibleComponents, "visibleComponents");
        patterns = immutable(patterns, "patterns");
        visibleText = immutable(visibleText, "visibleText");
        viewTypes = immutable(viewTypes, "viewTypes");
    }

    private static List<String> immutable(List<String> values, String field) {
        return List.copyOf(Objects.requireNonNull(values, field));
    }
}
