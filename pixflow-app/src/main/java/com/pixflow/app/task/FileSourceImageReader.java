package com.pixflow.app.task;

import com.pixflow.module.file.api.AssetImageQuery;
import com.pixflow.module.imagegen.port.SourceImageInfo;
import com.pixflow.module.imagegen.port.SourceImageReader;
import java.util.ArrayList;
import java.util.List;

/** Imagegen-owned source port 的 App adapter；File 不再编译依赖 Imagegen。 */
public final class FileSourceImageReader implements SourceImageReader {
    private final AssetImageQuery images;

    public FileSourceImageReader(AssetImageQuery images) {
        this.images = images;
    }

    @Override
    public List<SourceImageInfo> findAll(List<String> imageIds, String packageId) {
        long parsedPackageId;
        try {
            parsedPackageId = Long.parseLong(packageId);
        } catch (RuntimeException invalidPackageId) {
            return List.of();
        }
        List<Long> parsedImageIds = new ArrayList<>();
        for (String imageId : imageIds) {
            try {
                parsedImageIds.add(Long.parseLong(imageId));
            } catch (RuntimeException ignored) {
                // 非法 identity 在 owner query 中按 not found 处理。
            }
        }
        return images.findAll(parsedPackageId, parsedImageIds).stream()
                .map(image -> new SourceImageInfo(
                        Long.toString(image.imageId()), Long.toString(image.packageId()),
                        image.contentType()))
                .toList();
    }
}
