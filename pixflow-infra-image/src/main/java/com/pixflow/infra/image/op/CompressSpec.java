package com.pixflow.infra.image.op;

public record CompressSpec(Integer quality, Long targetBytes) {
    public CompressSpec {
        if (quality == null && targetBytes == null) {
            throw new IllegalArgumentException("quality or targetBytes must be provided");
        }
        if (quality != null && (quality < 1 || quality > 100)) {
            throw new IllegalArgumentException("quality must be between 1 and 100");
        }
        if (targetBytes != null && targetBytes <= 0) {
            throw new IllegalArgumentException("targetBytes must be positive");
        }
        if (quality != null && targetBytes != null) {
            throw new IllegalArgumentException("quality and targetBytes are mutually exclusive");
        }
    }
}
