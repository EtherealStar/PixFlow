package com.pixflow.module.file.internal.reference;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.contracts.asset.AssetReferenceKey;
import com.pixflow.contracts.asset.AssetReferenceKind;
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import com.pixflow.contracts.asset.PackageAssetReferenceKey;
import com.pixflow.contracts.asset.SkuAssetReferenceKey;
import com.pixflow.module.file.error.FileErrorCode;
import com.pixflow.module.file.api.AssetInspection;
import com.pixflow.module.file.api.AssetReferenceExpander;
import com.pixflow.module.file.api.AssetReferenceInspector;
import com.pixflow.module.file.api.AssetReferenceResolver;
import com.pixflow.module.file.api.AssetSourceType;
import com.pixflow.module.file.api.AssetUse;
import com.pixflow.module.file.api.ExpandedAssetSet;
import com.pixflow.module.file.api.ResolvedAssetReference;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.PackageStatus;
import com.pixflow.module.file.internal.deletion.AssetReferenceTombstoneMapper;
import com.pixflow.common.error.PixFlowException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** File 是唯一读取资产当前事实并执行 reference expansion 的边界。 */
public final class DefaultAssetReferenceService
        implements AssetReferenceResolver, AssetReferenceInspector, AssetReferenceExpander {
    private static final int INSPECTION_MAX_IMAGES = 1000;

    private final CanonicalAssetReferenceCodec codec;

    private final AssetPackageService packageService;

    private final AssetImageMapper imageMapper;

    private final AssetReferenceTombstoneMapper tombstoneMapper;

    public DefaultAssetReferenceService(
            CanonicalAssetReferenceCodec codec,
            AssetPackageService packageService,
            AssetImageMapper imageMapper) {
        this(codec, packageService, imageMapper, null);
    }

    public DefaultAssetReferenceService(
            CanonicalAssetReferenceCodec codec,
            AssetPackageService packageService,
            AssetImageMapper imageMapper,
            AssetReferenceTombstoneMapper tombstoneMapper) {
        this.codec = codec;
        this.packageService = packageService;
        this.imageMapper = imageMapper;
        this.tombstoneMapper = tombstoneMapper;
    }

    @Override
    public ResolvedAssetReference resolve(String referenceKey, AssetUse use) {
        if (use == null) {
            throw new IllegalArgumentException("asset use must not be null");
        }
        AssetReferenceKey key = codec.parse(referenceKey);
        if (key instanceof PackageAssetReferenceKey packageKey) {
            AssetPackage assetPackage = requireUsablePackage(key.packageId(), use);
            return packageView(packageKey, assetPackage);
        }
        if (key instanceof SkuAssetReferenceKey skuKey) {
            AssetPackage assetPackage = requireUsablePackage(key.packageId(), use);
            List<AssetImage> images = selectImages(key.packageId(), skuKey.skuId(), 1);
            requireProcessable(images, use, referenceKey);
            return new ResolvedAssetReference(
                    codec.serialize(skuKey), AssetReferenceKind.SKU, null,
                    skuKey.packageId(), null, skuKey.skuId(), assetPackage.getName());
        }
        ImageAssetReferenceKey imageKey = (ImageAssetReferenceKey) key;
        AssetImage image = imageMapper.selectById(imageKey.imageId());
        if (image == null || image.getPackageId() == null
                || imageKey.packageId() != image.getPackageId()
                || image.getDeletionStatus() != null
                || !"READY".equals(image.getPublicationStatus())) {
            throw notFound(referenceKey);
        }
        requireProcessable(List.of(image), use, referenceKey);
        String packageName;
        if (AssetSourceType.GENERATED.name().equals(image.getSourceType())) {
            AssetPackage currentPackage = packageServicePackage(imageKey.packageId());
            packageName = currentPackage == null ? tombstonePackageName(imageKey.packageId())
                    : currentPackage.getName();
        } else {
            packageName = requireUsablePackage(key.packageId(), use).getName();
        }
        return imageView(imageKey, image, packageName);
    }

    @Override
    public AssetInspection inspect(String referenceKey) {
        ResolvedAssetReference reference = resolve(referenceKey, AssetUse.INSPECT);
        List<ResolvedAssetReference> children = switch (reference.kind()) {
            case PACKAGE, SKU -> expand(
                    List.of(referenceKey), AssetUse.INSPECT, INSPECTION_MAX_IMAGES).images();
            case IMAGE -> List.of(reference);
        };
        return new AssetInspection(reference, children);
    }

    @Override
    public ExpandedAssetSet expand(List<String> referenceKeys, AssetUse use, int maxImages) {
        if (maxImages <= 0) {
            throw new IllegalArgumentException("maxImages must be positive");
        }
        if (referenceKeys == null || referenceKeys.isEmpty()) {
            return new ExpandedAssetSet(List.of());
        }
        Map<String, ResolvedAssetReference> unique = new LinkedHashMap<>();
        for (String referenceKey : referenceKeys) {
            if (unique.size() == maxImages) {
                break;
            }
            ResolvedAssetReference reference = resolve(referenceKey, use);
            if (reference.kind() == AssetReferenceKind.IMAGE) {
                unique.putIfAbsent(reference.referenceKey(), reference);
                continue;
            }
            int remaining = maxImages - unique.size();
            List<AssetImage> images = reference.kind() == AssetReferenceKind.SKU
                    ? selectImages(reference.packageId(), reference.skuId(), remaining)
                    : selectImages(reference.packageId(), null, remaining);
            for (AssetImage image : images) {
                if (unique.size() == maxImages) {
                    break;
                }
                if (image.getDeletionStatus() == null
                        && "READY".equals(image.getPublicationStatus())
                        && image.getMinioKey() != null
                        && !image.getMinioKey().isBlank()) {
                    ImageAssetReferenceKey imageKey = new ImageAssetReferenceKey(
                            reference.packageId(), image.getId());
                    unique.putIfAbsent(codec.serialize(imageKey),
                            imageView(imageKey, image, packageService.require(reference.packageId()).getName()));
                }
            }
        }
        return new ExpandedAssetSet(List.copyOf(unique.values()));
    }

    private ResolvedAssetReference packageView(PackageAssetReferenceKey key, AssetPackage assetPackage) {
        return new ResolvedAssetReference(
                codec.serialize(key), AssetReferenceKind.PACKAGE, null,
                key.packageId(), null, null, assetPackage.getName());
    }

    private ResolvedAssetReference imageView(
            ImageAssetReferenceKey key, AssetImage image, String packageName) {
        String imageName = image.getOriginalPath() == null
                ? "Generated Image " + image.getId() : image.getOriginalPath();
        String displayPath = packageName + " / " + imageName;
        return new ResolvedAssetReference(
                codec.serialize(key), AssetReferenceKind.IMAGE,
                AssetSourceType.valueOf(image.getSourceType()),
                key.packageId(), key.imageId(), image.getSkuId(), displayPath);
    }

    private AssetPackage requireUsablePackage(long packageId, AssetUse use) {
        AssetPackage assetPackage = packageService.require(packageId);
        if (assetPackage.getCleanupStatus() != null
                || !processable(assetPackage.getStatus())) {
            throw notFound("package:" + packageId);
        }
        return assetPackage;
    }

    private List<AssetImage> selectImages(long packageId, String skuId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        LambdaQueryWrapper<AssetImage> query = new LambdaQueryWrapper<AssetImage>()
                .eq(AssetImage::getPackageId, packageId)
                .eq(AssetImage::getPublicationStatus, "READY")
                .isNull(AssetImage::getDeletionStatus)
                // 在 owner 查询层排除不可处理图片，避免无效行提前耗尽 LIMIT。
                .isNotNull(AssetImage::getMinioKey)
                .apply("TRIM(minio_key) <> ''")
                .orderByAsc(AssetImage::getId)
                .last("LIMIT " + limit);
        if (skuId != null) {
            query.eq(AssetImage::getSkuId, skuId);
        }
        return imageMapper.selectList(query);
    }

    private static void requireProcessable(List<AssetImage> images, AssetUse use, String referenceKey) {
        boolean requiresBytes = use == AssetUse.PROCESS || use == AssetUse.DOWNLOAD;
        if (images.isEmpty() || requiresBytes && images.stream()
                .anyMatch(image -> image.getMinioKey() == null || image.getMinioKey().isBlank())) {
            throw notFound(referenceKey);
        }
    }

    private static boolean processable(PackageStatus status) {
        return status == PackageStatus.READY || status == PackageStatus.PARTIAL;
    }

    private AssetPackage packageServicePackage(long packageId) {
        try {
            return packageService.require(packageId);
        } catch (PixFlowException missing) {
            return null;
        }
    }

    private String tombstonePackageName(long packageId) {
        String displayName = tombstoneMapper == null ? null : tombstoneMapper.findPackageDisplayName(packageId);
        return displayName == null ? "Package " + packageId : displayName;
    }

    private static PixFlowException notFound(String referenceKey) {
        return new PixFlowException(FileErrorCode.PACKAGE_NOT_FOUND,
                "asset reference not found: " + referenceKey);
    }
}
