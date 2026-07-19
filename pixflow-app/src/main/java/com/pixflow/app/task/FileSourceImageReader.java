package com.pixflow.app.task;

import com.pixflow.module.file.api.AssetContentReader;
import com.pixflow.module.imagegen.port.SourceImageInfo;
import com.pixflow.module.imagegen.port.SourceImageReader;
import java.util.Optional;

/** Imagegen-owned source port 的 App adapter；File 不再编译依赖 Imagegen。 */
public final class FileSourceImageReader implements SourceImageReader {
    private final AssetContentReader contents;

    public FileSourceImageReader(AssetContentReader contents) {
        this.contents = contents;
    }

    @Override
    public Optional<SourceImageInfo> find(String referenceKey) {
        try {
            var image = contents.require(referenceKey);
            return Optional.of(new SourceImageInfo(image.referenceKey(), image.contentType()));
        } catch (RuntimeException ignored) {
            // File owner query 已统一校验 canonical key、归属和 READY 状态。
            return Optional.empty();
        }
    }
}
