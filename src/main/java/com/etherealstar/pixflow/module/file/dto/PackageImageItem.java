package com.etherealstar.pixflow.module.file.dto;

import com.etherealstar.pixflow.module.file.entity.AssetImage;

/**
 * 素材包详情中的图片项（需求 4.8）。
 *
 * @param imageId      图片 id
 * @param skuId        从文件名提取的 SKU ID
 * @param originalPath 相对 zip 根目录的相对路径
 */
public record PackageImageItem(Long imageId, String skuId, String originalPath) {

    public static PackageImageItem from(AssetImage image) {
        return new PackageImageItem(image.getId(), image.getSkuId(), image.getOriginalPath());
    }
}
