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
import com.pixflow.common.error.PixFlowException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** File 是唯一读取资产当前事实并执行 reference expansion 的边界。 */
public final class DefaultAssetReferenceService
        implements AssetReferenceResolver, AssetReferenceInspector, AssetReferenceExpander {
    private final CanonicalAssetReferenceCodec codec;

    private final AssetPackageService packageService;

    private final AssetImageMapper imageMapper;

    public DefaultAssetReferenceService(
            CanonicalAssetReferenceCodec codec,
            AssetPackageService packageService,
            AssetImageMapper imageMapper) {
        this.codec = codec;
        this.packageService = packageService;
        this.imageMapper = imageMapper;
    }

    @Override
    public ResolvedAssetReference resolve(String referenceKey, AssetUse use) {
        if (use == null) {
            throw new IllegalArgumentException("asset use must not be null");
        }
        AssetReferenceKey key = codec.parse(referenceKey);
        AssetPackage assetPackage = requireUsablePackage(key.packageId(), use);
        if (key instanceof PackageAssetReferenceKey packageKey) {
            return packageView(packageKey, assetPackage);
        }
        if (key instanceof SkuAssetReferenceKey skuKey) {
            List<AssetImage> images = selectImages(key.packageId(), skuKey.skuId());
            requireProcessable(images, use, referenceKey);
            return new ResolvedAssetReference(
                    codec.serialize(skuKey), AssetReferenceKind.SKU, null,
                    skuKey.packageId(), null, skuKey.skuId(), assetPackage.getName());
        }
        ImageAssetReferenceKey imageKey = (ImageAssetReferenceKey) key;
        AssetImage image = imageMapper.selectById(imageKey.imageId());
        if (image == null || image.getPackageId() == null
                || imageKey.packageId() != image.getPackageId()
                || image.getDeletedAt() != null
                || !"READY".equals(image.getPublicationStatus())) {
            throw notFound(referenceKey);
        }
        requireProcessable(List.of(image), use, referenceKey);
        return imageView(imageKey, image, assetPackage);
    }

    @Override
    public AssetInspection inspect(String referenceKey) {
        ResolvedAssetReference reference = resolve(referenceKey, AssetUse.INSPECT);
        List<ResolvedAssetReference> children = switch (reference.kind()) {
            case PACKAGE, SKU -> expand(List.of(referenceKey), AssetUse.INSPECT).images();
            case IMAGE -> List.of(reference);
        };
        return new AssetInspection(reference, children);
    }

    @Override
    public ExpandedAssetSet expand(List<String> referenceKeys, AssetUse use) {
        if (referenceKeys == null || referenceKeys.isEmpty()) {
            return new ExpandedAssetSet(List.of());
        }
        Map<String, ResolvedAssetReference> unique = new LinkedHashMap<>();
        for (String referenceKey : referenceKeys) {
            ResolvedAssetReference reference = resolve(referenceKey, use);
            if (reference.kind() == AssetReferenceKind.IMAGE) {
                unique.putIfAbsent(reference.referenceKey(), reference);
                continue;
            }
            List<AssetImage> images = reference.kind() == AssetReferenceKind.SKU
                    ? selectImages(reference.packageId(), reference.skuId())
                    : selectImages(reference.packageId(), null);
            for (AssetImage image : images) {
                if (image.getDeletedAt() == null
                        && "READY".equals(image.getPublicationStatus())
                        && image.getMinioKey() != null
                        && !image.getMinioKey().isBlank()) {
                    ImageAssetReferenceKey imageKey = new ImageAssetReferenceKey(
                            reference.packageId(), image.getId());
                    unique.putIfAbsent(codec.serialize(imageKey),
                            imageView(imageKey, image, packageService.require(reference.packageId())));
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
            ImageAssetReferenceKey key, AssetImage image, AssetPackage assetPackage) {
        String imageName = image.getOriginalPath() == null
                ? "Generated Image " + image.getId() : image.getOriginalPath();
        String displayPath = assetPackage.getName() + " / " + imageName;
        return new ResolvedAssetReference(
                codec.serialize(key), AssetReferenceKind.IMAGE,
                AssetSourceType.valueOf(image.getSourceType()),
                key.packageId(), key.imageId(), image.getSkuId(), displayPath);
    }

    private AssetPackage requireUsablePackage(long packageId, AssetUse use) {
        AssetPackage assetPackage = packageService.require(packageId);
        if (assetPackage.getDeletedAt() != null || !processable(assetPackage.getStatus())) {
            throw notFound("package:" + packageId);
        }
        return assetPackage;
    }

    private List<AssetImage> selectImages(long packageId, String skuId) {
        LambdaQueryWrapper<AssetImage> query = new LambdaQueryWrapper<AssetImage>()
                .eq(AssetImage::getPackageId, packageId)
                .eq(AssetImage::getPublicationStatus, "READY")
                .isNull(AssetImage::getDeletedAt)
                .orderByAsc(AssetImage::getId);
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

    private static PixFlowException notFound(String referenceKey) {
        return new PixFlowException(FileErrorCode.PACKAGE_NOT_FOUND,
                "asset reference not found: " + referenceKey);
    }
}
