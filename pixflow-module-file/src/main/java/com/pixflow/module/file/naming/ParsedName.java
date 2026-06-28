package com.pixflow.module.file.naming;

public record ParsedName(String groupKey, String skuId, String viewId) {
    public ParsedName {
        if (skuId == null || skuId.isBlank()) {
            throw new IllegalArgumentException("skuId must not be blank");
        }
        groupKey = normalizeNullable(groupKey);
        skuId = skuId.trim();
        viewId = normalizeNullable(viewId);
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
