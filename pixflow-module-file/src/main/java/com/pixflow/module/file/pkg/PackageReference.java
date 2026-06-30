package com.pixflow.module.file.pkg;

import java.util.List;

/**
 * 对 conversation 暴露的素材包只读引用视图。
 */
public record PackageReference(
        String packageId,
        String packageName,
        String objectPrefix,
        List<ImageReference> images) {

    public PackageReference {
        images = images == null ? List.of() : List.copyOf(images);
    }
}
