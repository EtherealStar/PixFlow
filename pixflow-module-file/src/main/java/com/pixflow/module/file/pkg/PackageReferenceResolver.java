package com.pixflow.module.file.pkg;

import java.util.List;

/**
 * 素材包引用解析 SPI。
 *
 * <p>conversation 只需要把素材包展开为 durable 图片引用，不直接读取 asset_* 表。
 */
public interface PackageReferenceResolver {
    PackageReference resolve(String packageId);

    List<ImageReference> listImages(String packageId);
}
