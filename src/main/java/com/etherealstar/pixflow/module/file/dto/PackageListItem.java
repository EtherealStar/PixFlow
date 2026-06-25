package com.etherealstar.pixflow.module.file.dto;

import com.etherealstar.pixflow.module.file.entity.AssetPackage;

import java.time.LocalDateTime;

/**
 * 素材包列表项（需求 4.2–4.4）。
 *
 * @param id         素材包 id
 * @param name       包名
 * @param size       上传 zip 体积（字节）
 * @param imageCount 成功识别图片数
 * @param status     状态（0 解析中 / 1 就绪 / 2 解析失败）
 * @param createdAt  创建时间
 */
public record PackageListItem(
        Long id,
        String name,
        Long size,
        Integer imageCount,
        Integer status,
        LocalDateTime createdAt) {

    public static PackageListItem from(AssetPackage pkg) {
        return new PackageListItem(
                pkg.getId(),
                pkg.getName(),
                pkg.getSize(),
                pkg.getImageCount(),
                pkg.getStatus(),
                pkg.getCreatedAt());
    }
}
