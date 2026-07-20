package com.pixflow.module.file.api.activity;

import java.util.List;

public record FileActivityPage(
        List<FileActivitySnapshot> records,
        long total,
        int page,
        int size) {
    public FileActivityPage {
        records = List.copyOf(records);
        if (total < 0 || page < 1 || size < 1) {
            throw new IllegalArgumentException("invalid file activity page");
        }
    }
}
