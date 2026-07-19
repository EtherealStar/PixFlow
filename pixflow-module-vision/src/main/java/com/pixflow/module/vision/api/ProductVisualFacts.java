package com.pixflow.module.vision.api;

import java.util.List;
import java.util.Objects;

/**
 * 图片中可直接观察到的当前商品视觉事实。
 */
public record ProductVisualFacts(
        CommonVisualFacts common,
        List<VisualAttribute> attributes,
        List<String> limitations,
        List<String> conflicts) {

    public ProductVisualFacts {
        common = Objects.requireNonNull(common, "common");
        attributes = List.copyOf(Objects.requireNonNull(attributes, "attributes"));
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        conflicts = List.copyOf(Objects.requireNonNull(conflicts, "conflicts"));
    }
}
