package com.pixflow.module.memory.ingest;

public class NoopMemoryIngestService implements MemoryIngestService {
    @Override
    public void ingestAsync(MemoryIngestRequest request) {
        // 第一阶段只保留异步巩固入口，实际抽取管线在后续里程碑接入。
    }
}
