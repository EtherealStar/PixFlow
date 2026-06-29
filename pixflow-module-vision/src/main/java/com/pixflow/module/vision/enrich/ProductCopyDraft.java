package com.pixflow.module.vision.enrich;

public record ProductCopyDraft(String productName, String keywords, String description) {
    public boolean isEmpty() {
        return isBlank(productName) && isBlank(keywords) && isBlank(description);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
