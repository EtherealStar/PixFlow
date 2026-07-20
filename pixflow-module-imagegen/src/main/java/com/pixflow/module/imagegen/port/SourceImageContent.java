package com.pixflow.module.imagegen.port;

import java.io.InputStream;

/**
 * Imagegen 属主定义的源图内容端口。
 *
 * <p>referenceKey 是不透明的 canonical handle；模块不得解析为 bucket/object key。
 */
public interface SourceImageContent {
    Metadata require(String referenceKey);

    InputStream open(String referenceKey);

    record Metadata(String contentType, long sizeBytes) {
        public Metadata {
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
        }
    }
}
