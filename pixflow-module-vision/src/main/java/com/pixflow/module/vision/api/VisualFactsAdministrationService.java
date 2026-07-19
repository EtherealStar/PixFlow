package com.pixflow.module.vision.api;

public interface VisualFactsAdministrationService {
    VisualFactsView get(long packageId, String skuId);

    VisualFactsView replace(long packageId, String skuId, ReplaceVisualFactsCommand command);

    VisualFactsView reanalyze(long packageId, String skuId, ReanalyzeVisualFactsCommand command);
}
