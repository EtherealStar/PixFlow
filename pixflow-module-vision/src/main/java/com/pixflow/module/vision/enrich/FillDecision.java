package com.pixflow.module.vision.enrich;

record FillDecision(boolean shouldExtract, boolean shouldWrite, ProductCopyDraft mergedDraft) {
}
