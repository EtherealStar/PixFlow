package com.pixflow.module.memory.insight;

public interface InsightIndexRebuildService {
    RebuildResult rebuildActiveIndex();

    record RebuildResult(int scanned, int upserted) {
    }
}
