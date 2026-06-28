package com.pixflow.module.memory.insight;

public class NoopInsightIndexRebuildService implements InsightIndexRebuildService {
    @Override
    public RebuildResult rebuildActiveIndex() {
        return new RebuildResult(0, 0);
    }
}
