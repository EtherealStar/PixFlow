package com.pixflow.module.file.internal.image;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.module.file.api.AssetImageDescriptor;
import com.pixflow.module.file.api.AssetImageQuery;
import com.pixflow.module.file.api.AssetSourceType;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import java.util.List;

/** 只投影 READY、未删除图片，Generated Image 与 Original Image 共用此边界。 */
public final class DefaultAssetImageQuery implements AssetImageQuery {
    private final AssetImageMapper mapper;

    public DefaultAssetImageQuery(AssetImageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<AssetImageDescriptor> listReady(long packageId) {
        if (packageId <= 0) {
            return List.of();
        }
        return query(packageId, null);
    }

    @Override
    public List<AssetImageDescriptor> findAll(long packageId, List<Long> imageIds) {
        if (packageId <= 0 || imageIds == null || imageIds.isEmpty()) {
            return List.of();
        }
        return query(packageId, imageIds);
    }

    private List<AssetImageDescriptor> query(long packageId, List<Long> imageIds) {
        LambdaQueryWrapper<AssetImage> query = new LambdaQueryWrapper<AssetImage>()
                        .eq(AssetImage::getPackageId, packageId)
                        .eq(AssetImage::getPublicationStatus, "READY")
                        .isNull(AssetImage::getDeletedAt)
                        .orderByAsc(AssetImage::getId);
        if (imageIds != null) {
            query.in(AssetImage::getId, imageIds);
        }
        return mapper.selectList(query)
                .stream()
                .map(DefaultAssetImageQuery::descriptor)
                .toList();
    }

    @Override
    public AssetImageDescriptor require(long packageId, long imageId) {
        return findAll(packageId, List.of(imageId)).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("READY image not found"));
    }

    @Override
    public AssetImageDescriptor require(long imageId) {
        AssetImage image = mapper.selectById(imageId);
        if (image == null || image.getDeletedAt() != null
                || !"READY".equals(image.getPublicationStatus())) {
            throw new IllegalArgumentException("READY image not found");
        }
        return descriptor(image);
    }

    private static AssetImageDescriptor descriptor(AssetImage image) {
        AssetSourceType sourceType = AssetSourceType.valueOf(image.getSourceType());
        BucketType bucket = sourceType == AssetSourceType.GENERATED
                ? BucketType.valueOf(image.getStableBucket()) : BucketType.PACKAGES;
        String contentType = image.getContentType() == null
                ? inferContentType(image.getMinioKey()) : image.getContentType();
        return new AssetImageDescriptor(
                image.getId(), image.getPackageId(), image.getSkuId(), image.getViewId(),
                image.getGroupKey(), sourceType,
                ObjectLocation.of(bucket, image.getMinioKey()), contentType);
    }

    private static String inferContentType(String key) {
        String lower = key == null ? "" : key.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }
}
