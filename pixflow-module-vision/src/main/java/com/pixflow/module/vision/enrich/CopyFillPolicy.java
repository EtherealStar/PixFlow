package com.pixflow.module.vision.enrich;

import com.pixflow.module.vision.config.VisionProperties;

public class CopyFillPolicy {
    private final VisionProperties properties;

    public CopyFillPolicy(VisionProperties properties) {
        this.properties = properties;
    }

    public FillDecision decide(AssetCopyRow existing, ProductCopyDraft draft) {
        if (existing == null) {
            return new FillDecision(true, draft != null && !draft.isEmpty(), draft);
        }
        if (properties.getEnrich().getFillPolicy() == VisionProperties.FillPolicy.SKIP_IF_ANY && existing.hasAnyField()) {
            return new FillDecision(false, false, null);
        }
        ProductCopyDraft merged = new ProductCopyDraft(
                choose(existing.getProductName(), draft == null ? null : draft.productName()),
                choose(existing.getKeywords(), draft == null ? null : draft.keywords()),
                choose(existing.getDescription(), draft == null ? null : draft.description()));
        boolean shouldWrite = draft != null && !draft.isEmpty()
                && (!equalsText(existing.getProductName(), merged.productName())
                || !equalsText(existing.getKeywords(), merged.keywords())
                || !equalsText(existing.getDescription(), merged.description()));
        boolean shouldExtract = !hasText(existing.getProductName())
                || !hasText(existing.getKeywords())
                || !hasText(existing.getDescription());
        return new FillDecision(shouldExtract, shouldWrite, merged);
    }

    private String choose(String existing, String draft) {
        return hasText(existing) ? existing : normalize(draft);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean equalsText(String left, String right) {
        return java.util.Objects.equals(normalize(left), normalize(right));
    }
}
