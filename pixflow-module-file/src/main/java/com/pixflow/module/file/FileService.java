package com.pixflow.module.file;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.web.PageResponse;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import com.pixflow.module.file.api.AssetSourceType;
import com.pixflow.module.file.api.AssetDeletionService;
import com.pixflow.infra.storage.StoredObjectMetadata;
import com.pixflow.module.file.error.AssetIngestError;
import com.pixflow.module.file.error.AssetIngestErrorMapper;
import com.pixflow.module.file.error.MaterialIngestErrorView;
import com.pixflow.module.file.error.FileErrorCode;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.image.AssetImageView;
import com.pixflow.module.file.image.AssetImageDetailView;
import com.pixflow.module.file.image.AssetSkuView;
import com.pixflow.module.file.image.OriginalImageSort;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageMapper;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.MaterialPackageView;
import com.pixflow.module.file.pkg.MaterialPackageSort;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Locale;

public class FileService {
    private static final Duration IMAGE_PREVIEW_TTL = Duration.ofMinutes(15);

    private final AssetPackageService packageService;

    private final AssetPackageMapper packageMapper;

    private final AssetImageMapper imageMapper;

    private final AssetIngestErrorMapper errorMapper;

    private final ObjectStorage objectStorage;

    private final CanonicalAssetReferenceCodec referenceCodec;

    private final AssetDeletionService deletionService;

    private final Clock clock;

    public FileService(
            AssetPackageService packageService,
            AssetPackageMapper packageMapper,
            AssetImageMapper imageMapper,
            AssetIngestErrorMapper errorMapper,
            ObjectStorage objectStorage,
            CanonicalAssetReferenceCodec referenceCodec,
            AssetDeletionService deletionService,
            Clock clock) {
        this.packageService = packageService;
        this.packageMapper = packageMapper;
        this.imageMapper = imageMapper;
        this.errorMapper = errorMapper;
        this.objectStorage = objectStorage;
        this.referenceCodec = referenceCodec;
        this.deletionService = deletionService;
        this.clock = clock;
    }

    public MaterialPackageView detail(long packageId) {
        return toPackageView(packageService.require(packageId));
    }

    public PageResponse<MaterialPackageView> list(
            long page, long size, String queryText, MaterialPackageSort sort) {
        LambdaQueryWrapper<AssetPackage> query = packageService.visiblePackages();
        if (queryText != null && !queryText.isBlank()) {
            query.like(AssetPackage::getName, queryText.trim());
        }
        applyPackageSort(query, sort == null ? MaterialPackageSort.UPDATED_DESC : sort);
        IPage<AssetPackage> result = packageMapper.selectPage(new Page<>(page, size), query);
        return new PageResponse<>(result.getRecords().stream().map(this::toPackageView).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public PageResponse<AssetSkuView> skus(long packageId, long page, long size) {
        packageService.require(packageId);
        long total = imageMapper.countReadyOriginalSkus(packageId, List.of());
        List<AssetSkuView> records = imageMapper.listReadyOriginalSkus(
                        packageId, List.of(), (page - 1) * size, size).stream()
                .map(skuId -> new AssetSkuView(packageId, skuId,
                        imageMapper.selectCount(new LambdaQueryWrapper<AssetImage>()
                                .eq(AssetImage::getPackageId, packageId)
                                .eq(AssetImage::getSkuId, skuId)
                                .eq(AssetImage::getSourceType, "ORIGINAL")
                                .eq(AssetImage::getPublicationStatus, "READY")
                                .isNull(AssetImage::getDeletionStatus))))
                .toList();
        return new PageResponse<>(records, total, page, size);
    }

    public PageResponse<AssetImageView> globalImages(
            Long packageId,
            String skuId,
            String queryText,
            long page,
            long size,
            OriginalImageSort sort) {
        LambdaQueryWrapper<AssetImage> query = new LambdaQueryWrapper<AssetImage>()
                .eq(AssetImage::getSourceType, "ORIGINAL")
                .eq(AssetImage::getPublicationStatus, "READY")
                .isNull(AssetImage::getDeletionStatus);
        if (packageId != null) {
            query.eq(AssetImage::getPackageId, packageId);
        }
        if (skuId != null && !skuId.isBlank()) {
            query.eq(AssetImage::getSkuId, skuId);
        }
        if (queryText != null && !queryText.isBlank()) {
            String term = queryText.trim();
            query.and(item -> item.like(AssetImage::getDisplayName, term)
                    .or().like(AssetImage::getOriginalPath, term)
                    .or().like(AssetImage::getSkuId, term));
        }
        applyImageSort(query, sort == null ? OriginalImageSort.CREATED_DESC : sort);
        IPage<AssetImage> result = imageMapper.selectPage(new Page<>(page, size), query);
        return new PageResponse<>(result.getRecords().stream().map(this::toView).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    public PageResponse<MaterialIngestErrorView> errors(long packageId, long page, long size) {
        packageService.require(packageId);
        IPage<AssetIngestError> result = errorMapper.selectPage(
                new Page<>(page, size),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AssetIngestError>()
                        .eq(AssetIngestError::getPackageId, packageId)
                        .orderByDesc(AssetIngestError::getCreatedAt));
        return new PageResponse<>(result.getRecords().stream()
                .map(error -> new MaterialIngestErrorView(
                        packageId,
                        error.getStage() == null ? null : error.getStage().name(),
                        error.getCode(),
                        error.getMessage(),
                        error.getCreatedAt()))
                .toList(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    public PageResponse<AssetImageView> images(
            long packageId,
            String skuId,
            String queryText,
            long page,
            long size,
            OriginalImageSort sort) {
        packageService.require(packageId);
        return globalImages(packageId, skuId, queryText, page, size, sort);
    }

    public AssetImageDetailView imageDetail(long packageId, long imageId) {
        AssetImage current = requireImage(packageId, imageId);
        if (!"ORIGINAL".equals(current.getSourceType())) {
            throw new PixFlowException(FileErrorCode.ASSET_IMAGE_NOT_FOUND, "original image not found");
        }
        List<AssetImage> ordered = imageMapper.selectList(new LambdaQueryWrapper<AssetImage>()
                .eq(AssetImage::getPackageId, packageId)
                .eq(AssetImage::getSourceType, "ORIGINAL")
                .eq(AssetImage::getPublicationStatus, "READY")
                .isNull(AssetImage::getDeletionStatus)
                .orderByAsc(AssetImage::getId));
        int index = java.util.stream.IntStream.range(0, ordered.size())
                .filter(position -> ordered.get(position).getId() == imageId)
                .findFirst()
                .orElseThrow();
        String previous = index == 0 ? null : String.valueOf(ordered.get(index - 1).getId());
        String next = index + 1 == ordered.size() ? null : String.valueOf(ordered.get(index + 1).getId());
        return new AssetImageDetailView(toView(current), previous, next);
    }

    public void deleteImage(long packageId, long imageId) {
        requireOriginalImage(packageId, imageId);
        deletionService.deleteImage(packageId, imageId);
    }

    public AssetImageView renameImage(long packageId, long imageId, String displayName) {
        AssetImage image = requireOriginalImage(packageId, imageId);
        String normalized = normalizeDisplayName(displayName);
        AssetImage update = new AssetImage();
        update.setId(image.getId());
        // displayName 是用户可见名；originalPath 仍保留 zip 内原始路径用于幂等和审计。
        update.setDisplayName(normalized);
        imageMapper.updateById(update);
        image.setDisplayName(normalized);
        return toView(image);
    }

    public void delete(long packageId) {
        deletionService.deletePackage(packageId);
    }

    public MaterialPackageView renamePackage(long packageId, String displayName) {
        AssetPackage assetPackage = packageService.require(packageId);
        String normalized = normalizeDisplayName(displayName);
        if (normalized == null) {
            throw new PixFlowException(FileErrorCode.ASSET_IMAGE_NAME_INVALID,
                    "package display name is required");
        }
        AssetPackage update = new AssetPackage();
        update.setId(packageId);
        update.setName(normalized);
        packageMapper.updateById(update);
        assetPackage.setName(normalized);
        return toPackageView(assetPackage);
    }

    public void cancelExtraction(long packageId) {
        AssetPackage assetPackage = packageMapper.selectById(packageId);
        if (assetPackage == null) {
            return;
        }
        if (assetPackage.getStatus() == com.pixflow.module.file.pkg.PackageStatus.READY
                || assetPackage.getStatus() == com.pixflow.module.file.pkg.PackageStatus.PARTIAL
                || assetPackage.getStatus() == com.pixflow.module.file.pkg.PackageStatus.FAILED) {
            throw new PixFlowException(FileErrorCode.PACKAGE_ALREADY_REFERENCED,
                    "terminal package cannot be cancelled");
        }
        deletionService.deletePackage(packageId);
    }

    private AssetImage requireImage(long packageId, long imageId) {
        packageService.require(packageId);
        AssetImage image = imageMapper.selectOne(new LambdaQueryWrapper<AssetImage>()
                .eq(AssetImage::getPackageId, packageId)
                .eq(AssetImage::getId, imageId)
                .eq(AssetImage::getPublicationStatus, "READY")
                .isNull(AssetImage::getDeletionStatus)
                .last("limit 1"));
        if (image == null) {
            throw new PixFlowException(FileErrorCode.ASSET_IMAGE_NOT_FOUND,
                    "asset image not found: " + imageId);
        }
        return image;
    }

    private AssetImage requireOriginalImage(long packageId, long imageId) {
        AssetImage image = requireImage(packageId, imageId);
        if (!"ORIGINAL".equals(image.getSourceType())) {
            throw new PixFlowException(FileErrorCode.ASSET_IMAGE_NOT_FOUND, "original image not found");
        }
        return image;
    }

    private AssetImageView toView(AssetImage image) {
        AssetSourceType sourceType = AssetSourceType.valueOf(image.getSourceType());
        BucketType bucket = sourceType == AssetSourceType.GENERATED
                ? BucketType.valueOf(image.getStableBucket()) : BucketType.PACKAGES;
        ObjectLocation location = ObjectLocation.of(bucket, image.getMinioKey());
        URL url = objectStorage.presignGet(location, IMAGE_PREVIEW_TTL);
        // 预签名 URL 的公开到期时间必须与应用时钟一致，便于测试和统一时间边界。
        Instant previewExpiresAt = clock.instant().plus(IMAGE_PREVIEW_TTL);
        return new AssetImageView(
                String.valueOf(image.getId()),
                image.getPackageId(),
                referenceCodec.serialize(new ImageAssetReferenceKey(image.getPackageId(), image.getId())),
                sourceType,
                image.getDisplayName(),
                image.getSkuId(),
                image.getWidth(),
                image.getHeight(),
                image.getByteSize() == null ? objectSize(location) : image.getByteSize(),
                image.getContentType(),
                url.toString(),
                previewExpiresAt,
                image.getCreatedAt());
    }

    private MaterialPackageView toPackageView(AssetPackage assetPackage) {
        long packageId = assetPackage.getId();
        long skuCount = imageMapper.countReadyOriginalSkus(packageId, List.of());
        long imageCount = assetPackage.getImageCount() == null ? 0L : assetPackage.getImageCount();
        return new MaterialPackageView(
                packageId,
                assetPackage.getName(),
                assetPackage.getStatus(),
                imageCount,
                skuCount,
                assetPackage.getCreatedAt(),
                assetPackage.getUpdatedAt());
    }

    private static void applyPackageSort(
            LambdaQueryWrapper<AssetPackage> query, MaterialPackageSort sort) {
        switch (sort) {
            case UPDATED_DESC -> query.orderByDesc(AssetPackage::getUpdatedAt);
            case UPDATED_ASC -> query.orderByAsc(AssetPackage::getUpdatedAt);
            case NAME_ASC -> query.orderByAsc(AssetPackage::getName);
            case NAME_DESC -> query.orderByDesc(AssetPackage::getName);
        }
    }

    private static void applyImageSort(
            LambdaQueryWrapper<AssetImage> query, OriginalImageSort sort) {
        switch (sort) {
            case CREATED_DESC -> query.orderByDesc(AssetImage::getCreatedAt);
            case CREATED_ASC -> query.orderByAsc(AssetImage::getCreatedAt);
            case NAME_ASC -> query.orderByAsc(AssetImage::getDisplayName);
            case NAME_DESC -> query.orderByDesc(AssetImage::getDisplayName);
        }
    }

    private Long objectSize(ObjectLocation location) {
        try {
            StoredObjectMetadata metadata = objectStorage.stat(location);
            return metadata == null ? null : metadata.size();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            return null;
        }
        String normalized = displayName.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > 255 || normalized.contains("/") || normalized.contains("\\")
                || normalized.toLowerCase(Locale.ROOT).contains("..")) {
            throw new PixFlowException(FileErrorCode.ASSET_IMAGE_NAME_INVALID, "invalid asset image display name");
        }
        return normalized;
    }
}
