package com.pixflow.module.file.api.publication;

/** File-owned producer provenance。 */
public record GeneratedImageProducer(GeneratedImageKind kind, String provider, String model,
                                     String tool, String nodeId) {
    public GeneratedImageProducer {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (kind == GeneratedImageKind.GENERATIVE) {
            requireText(provider, "provider");
            requireText(model, "model");
            requireNull(tool, "tool");
            requireNull(nodeId, "nodeId");
        } else {
            requireText(tool, "tool");
            requireText(nodeId, "nodeId");
            requireNull(provider, "provider");
            requireNull(model, "model");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireNull(String value, String name) {
        if (value != null) {
            throw new IllegalArgumentException(name + " must be null");
        }
    }
}
