package com.pixflow.module.vision.api;

public record VisualFactsScope(
        String skuReferenceKey,
        String imageReferenceKey,
        ProductVisualFacts skuFacts,
        ProductVisualFacts imageFacts) {
}
