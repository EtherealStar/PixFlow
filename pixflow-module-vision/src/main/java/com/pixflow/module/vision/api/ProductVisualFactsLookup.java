package com.pixflow.module.vision.api;

@FunctionalInterface
public interface ProductVisualFactsLookup {
    VisualFactsLookupResult lookup(String referenceKey);
}
