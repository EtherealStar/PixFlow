package com.etherealstar.pixflow.module.file.dto;

import com.etherealstar.pixflow.module.file.entity.AssetPackage;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 素材包详情响应（需求 4.8）。
 *
 * <p>返回素材包基础信息及其包含的图片列表（每项含 {@code imageId}、{@code skuId}、{@code originalPath}）。</p>
 *
 * @param id         素材包 id
 * @param name       包名
 * @param size       上传 zip 体积（字节）
 * @param imageCount 成功识别图片数
 * @param status     状态（0 解析中 / 1 就绪 / 2 解析失败）
 * @param createdAt  创建时间
 * @param images     图片列表
 */
public record PackageDetailResponse(
        Long id,
        String name,
        Long size,
        Integer imageCount,
        Integer status,
        LocalDateTime createdAt,
        List<PackageImageItem> images) {

    public static PackageDetailResponse from(AssetPackage pkg, List<PackageImageItem> images) {
        return new PackageDetailResponse(
                pkg.getId(),
                pkg.getName(),
                pkg.getSize(),
                pkg.getImageCount(),
                pkg.getStatus(),
                pkg.getCreatedAt(),
                images);
    }
}
