package com.pixflow.module.file.internal.reference;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pixflow.common.web.PageResponse;
import com.pixflow.contracts.asset.AssetReferenceKey;
import com.pixflow.contracts.asset.AssetReferenceKind;
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import com.pixflow.contracts.asset.PackageAssetReferenceKey;
import com.pixflow.contracts.asset.SkuAssetReferenceKey;
import com.pixflow.module.file.api.AssetReferenceCandidate;
import com.pixflow.module.file.api.AssetReferenceCatalog;
import com.pixflow.module.file.api.AssetReferenceSource;
import com.pixflow.module.file.api.AssetSourceType;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageMapper;
import com.pixflow.module.file.pkg.PackageStatus;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DefaultAssetReferenceCatalog implements AssetReferenceCatalog {
    private static final int MAX_EXCLUSIONS = 20;

    private final CanonicalAssetReferenceCodec codec;

    private final AssetPackageMapper packageMapper;

    private final AssetImageMapper imageMapper;

    public DefaultAssetReferenceCatalog(CanonicalAssetReferenceCodec codec,
                                        AssetPackageMapper packageMapper,
                                        AssetImageMapper imageMapper) {
        this.codec = codec;
        this.packageMapper = packageMapper;
        this.imageMapper = imageMapper;
    }

    @Override
    public PageResponse<AssetReferenceCandidate> list(
            AssetReferenceSource source,
            String parentKey,
            String query,
            long page,
            long size,
            List<String> excludedReferenceKeys) {
        validate(source, parentKey, query, page, size, excludedReferenceKeys);
        Set<String> excluded = canonicalExclusions(excludedReferenceKeys);
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery != null) {
            return searchBoth(normalizedQuery, page, size, excluded);
        }
        if (source != null) {
            if (source == AssetReferenceSource.OUTPUTS) {
                return imagePage(null, null, null, AssetSourceType.GENERATED, page, size, excluded);
            }
            return packagePage(page, size, excluded);
        }
        AssetReferenceKey parent = codec.parse(parentKey);
        if (parent instanceof PackageAssetReferenceKey packageKey) {
            return skuPage(packageKey.packageId(), page, size, excluded);
        }
        if (parent instanceof SkuAssetReferenceKey skuKey) {
            return imagePage(null, skuKey.packageId(), skuKey.skuId(),
                    AssetSourceType.ORIGINAL, page, size, excluded);
        }
        throw new IllegalArgumentException("IMAGE reference cannot be a parent");
    }

    @Override
    public PageResponse<AssetReferenceCandidate> listGeneratedByTaskId(
            long taskId, long page, long size, List<String> excludedReferenceKeys) {
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        validatePage(page, size, excludedReferenceKeys);
        Set<String> excluded = canonicalExclusions(excludedReferenceKeys);
        LambdaQueryWrapper<AssetImage> query = new LambdaQueryWrapper<AssetImage>()
                .eq(AssetImage::getSourceType, AssetSourceType.GENERATED.name())
                .eq(AssetImage::getPublicationStatus, "READY")
                .eq(AssetImage::getSourceTaskId, taskId)
                .isNull(AssetImage::getDeletionStatus)
                .orderByDesc(AssetImage::getCreatedAt);
        excludeImageIds(query, excluded);
        IPage<AssetImage> result = imageMapper.selectPage(new Page<>(page, size), query);
        List<AssetReferenceCandidate> items = result.getRecords().stream()
                .map(image -> imageCandidate(image, null))
                .toList();
        return new PageResponse<>(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private PageResponse<AssetReferenceCandidate> packagePage(
            long page, long size, Set<String> excluded) {
        LambdaQueryWrapper<AssetPackage> query = new LambdaQueryWrapper<AssetPackage>()
                .in(AssetPackage::getStatus, PackageStatus.READY, PackageStatus.PARTIAL)
                .isNull(AssetPackage::getCleanupStatus)
                .orderByDesc(AssetPackage::getCreatedAt);
        List<Long> excludedIds = excluded.stream().map(codec::parse)
                .filter(PackageAssetReferenceKey.class::isInstance)
                .map(PackageAssetReferenceKey.class::cast)
                .map(PackageAssetReferenceKey::packageId).toList();
        if (!excludedIds.isEmpty()) {
            query.notIn(AssetPackage::getId, excludedIds);
        }
        IPage<AssetPackage> result = packageMapper.selectPage(new Page<>(page, size), query);
        List<AssetReferenceCandidate> items = result.getRecords().stream()
                .map(item -> new AssetReferenceCandidate(
                        codec.serialize(new PackageAssetReferenceKey(item.getId())),
                        AssetReferenceKind.PACKAGE, null, item.getName(), true,
                        null))
                .toList();
        return new PageResponse<>(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private PageResponse<AssetReferenceCandidate> skuPage(
            long packageId, long page, long size, Set<String> excluded) {
        AssetPackage assetPackage = requireVisiblePackage(packageId);
        List<String> excludedSkuIds = excluded.stream().map(codec::parse)
                .filter(SkuAssetReferenceKey.class::isInstance)
                .map(SkuAssetReferenceKey.class::cast)
                .filter(key -> key.packageId() == packageId)
                .map(SkuAssetReferenceKey::skuId)
                .toList();
        // exact exclusion 必须进入 SQL，确保排除发生在分页之前。
        long total = imageMapper.countReadyOriginalSkus(packageId, excludedSkuIds);
        List<String> records = imageMapper.listReadyOriginalSkus(
                packageId, excludedSkuIds, (page - 1) * size, size);
        List<AssetReferenceCandidate> candidates = records.stream()
                .map(sku -> new SkuAssetReferenceKey(packageId, sku))
                .map(key -> new AssetReferenceCandidate(codec.serialize(key), AssetReferenceKind.SKU,
                        null, assetPackage.getName() + " / " + key.skuId(), true,
                        null))
                .toList();
        return new PageResponse<>(candidates, total, page, size);
    }

    private PageResponse<AssetReferenceCandidate> imagePage(
            AssetReferenceSource source, Long packageId, String skuId,
            AssetSourceType sourceType, long page, long size, Set<String> excluded) {
        LambdaQueryWrapper<AssetImage> query = new LambdaQueryWrapper<AssetImage>()
                .eq(AssetImage::getSourceType, sourceType.name())
                .eq(AssetImage::getPublicationStatus, "READY")
                .isNull(AssetImage::getDeletionStatus)
                .orderByDesc(AssetImage::getCreatedAt);
        if (packageId != null) {
            requireVisiblePackage(packageId);
            query.eq(AssetImage::getPackageId, packageId);
        }
        if (skuId != null) {
            query.eq(AssetImage::getSkuId, skuId);
        }
        excludeImageIds(query, excluded);
        IPage<AssetImage> result = imageMapper.selectPage(new Page<>(page, size), query);
        List<AssetReferenceCandidate> items = result.getRecords().stream()
                .map(image -> imageCandidate(image, source))
                .toList();
        return new PageResponse<>(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private PageResponse<AssetReferenceCandidate> search(
            AssetReferenceSource source, String query, long page, long size, Set<String> excluded) {
        AssetSourceType type = source == AssetReferenceSource.OUTPUTS
                ? AssetSourceType.GENERATED : AssetSourceType.ORIGINAL;
        LambdaQueryWrapper<AssetImage> wrapper = new LambdaQueryWrapper<AssetImage>()
                .eq(AssetImage::getSourceType, type.name())
                .eq(AssetImage::getPublicationStatus, "READY")
                .isNull(AssetImage::getDeletionStatus)
                .and(condition -> condition.like(AssetImage::getDisplayName, query)
                        .or().like(AssetImage::getOriginalPath, query)
                        .or().like(AssetImage::getSkuId, query))
                .orderByDesc(AssetImage::getCreatedAt);
        List<Long> excludedIds = excluded.stream().map(codec::parse)
                .filter(ImageAssetReferenceKey.class::isInstance)
                .map(ImageAssetReferenceKey.class::cast)
                .map(ImageAssetReferenceKey::imageId).toList();
        if (!excludedIds.isEmpty()) {
            wrapper.notIn(AssetImage::getId, excludedIds);
        }
        IPage<AssetImage> result = imageMapper.selectPage(new Page<>(page, size), wrapper);
        List<AssetReferenceCandidate> items = result.getRecords().stream()
                .map(image -> imageCandidate(image, source)).toList();
        return new PageResponse<>(items, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private PageResponse<AssetReferenceCandidate> searchBoth(
            String query, long page, long size, Set<String> excluded) {
        long fetchSize = Math.min(100L, page * size);
        List<AssetReferenceCandidate> materials = search(
                AssetReferenceSource.MATERIALS, query, 1, fetchSize, excluded).records();
        List<AssetReferenceCandidate> outputs = search(
                AssetReferenceSource.OUTPUTS, query, 1, fetchSize, excluded).records();
        List<AssetReferenceCandidate> combined = java.util.stream.Stream.concat(
                        materials.stream(), outputs.stream())
                .sorted(java.util.Comparator.comparing(AssetReferenceCandidate::displayPath))
                .toList();
        return slice(combined, page, size);
    }

    private AssetReferenceCandidate imageCandidate(AssetImage image, AssetReferenceSource source) {
        AssetPackage assetPackage = packageMapper.selectById(image.getPackageId());
        String packageName = assetPackage == null ? "Package " + image.getPackageId() : assetPackage.getName();
        String imageName = image.getDisplayName() == null || image.getDisplayName().isBlank()
                ? image.getOriginalPath() == null ? "Generated Image " + image.getId() : image.getOriginalPath()
                : image.getDisplayName();
        String skuPart = image.getSkuId() == null ? "" : " / " + image.getSkuId();
        return new AssetReferenceCandidate(
                codec.serialize(new ImageAssetReferenceKey(image.getPackageId(), image.getId())),
                AssetReferenceKind.IMAGE, AssetSourceType.valueOf(image.getSourceType()),
                packageName + skuPart + " / " + imageName, false, source);
    }

    private AssetPackage requireVisiblePackage(long packageId) {
        AssetPackage assetPackage = packageMapper.selectById(packageId);
        if (assetPackage == null || assetPackage.getCleanupStatus() != null
                || assetPackage.getStatus() != PackageStatus.READY
                && assetPackage.getStatus() != PackageStatus.PARTIAL) {
            throw new IllegalArgumentException("processable package not found");
        }
        return assetPackage;
    }

    private Set<String> canonicalExclusions(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        for (String value : values) {
            AssetReferenceKey parsed = codec.parse(value);
            result.add(codec.serialize(parsed));
        }
        return result;
    }

    private void excludeImageIds(LambdaQueryWrapper<AssetImage> query, Set<String> excluded) {
        List<Long> excludedIds = excluded.stream().map(codec::parse)
                .filter(ImageAssetReferenceKey.class::isInstance)
                .map(ImageAssetReferenceKey.class::cast)
                .map(ImageAssetReferenceKey::imageId).toList();
        if (!excludedIds.isEmpty()) {
            query.notIn(AssetImage::getId, excludedIds);
        }
    }

    private static void validate(
            AssetReferenceSource source,
            String parentKey,
            String query,
            long page,
            long size,
            List<String> exclusions) {
        int selectors = source == null ? 0 : 1;
        selectors += parentKey == null || parentKey.isBlank() ? 0 : 1;
        selectors += query == null || query.isBlank() ? 0 : 1;
        if (selectors != 1) {
            throw new IllegalArgumentException("exactly one asset reference selector is required");
        }
        validatePage(page, size, exclusions);
    }

    private static void validatePage(long page, long size, List<String> exclusions) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("invalid asset reference page request");
        }
        if (exclusions != null && exclusions.size() > MAX_EXCLUSIONS) {
            throw new IllegalArgumentException("at most 20 reference exclusions are allowed");
        }
    }

    private static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return query.trim().toLowerCase(Locale.ROOT);
    }

    private static PageResponse<AssetReferenceCandidate> slice(
            List<AssetReferenceCandidate> items, long page, long size) {
        long start = Math.min(items.size(), (page - 1) * size);
        long end = Math.min(items.size(), start + size);
        return new PageResponse<>(items.subList((int) start, (int) end), items.size(), page, size);
    }
}
