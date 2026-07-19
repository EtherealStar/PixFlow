package com.pixflow.module.file.ingest;

public interface ArchiveExtractor {
    ArchiveFormat format();

    void extract(long packageId);
}
