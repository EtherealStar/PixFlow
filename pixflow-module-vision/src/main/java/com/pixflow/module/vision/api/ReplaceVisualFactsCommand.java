package com.pixflow.module.vision.api;

import java.util.Objects;

public record ReplaceVisualFactsCommand(long expectedVersion, ProductVisualFacts facts) {
    public ReplaceVisualFactsCommand {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must be non-negative");
        }
        facts = Objects.requireNonNull(facts, "facts");
    }
}
