package com.pixflow.module.commerce.importer;

public record RawCommerceRow(
        int rowNumber,
        String skuId,
        String category,
        String impressions,
        String ctr,
        String addCartRate,
        String purchaseRate,
        String periodType,
        String periodStart,
        String periodEnd) {
}
