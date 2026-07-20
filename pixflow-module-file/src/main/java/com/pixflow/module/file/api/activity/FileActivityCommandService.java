package com.pixflow.module.file.api.activity;

public interface FileActivityCommandService {
    void cancel(FileActivitySourceKind sourceKind, String sourceId);

    void clear(FileActivitySourceKind sourceKind, String sourceId);
}
