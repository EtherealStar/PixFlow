package com.pixflow.module.file.api.activity;

import java.util.Optional;

public interface FileActivitySource {
    Optional<FileActivitySnapshot> find(FileActivitySourceKind kind, String sourceId);

    FileActivityPage listCurrent(int page, int size);
}
