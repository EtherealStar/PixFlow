package com.pixflow.module.file.internal.deletion;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.file.api.AssetDeletionService;
import com.pixflow.module.file.copydoc.AssetCopy;
import com.pixflow.module.file.copydoc.AssetCopyMapper;
import com.pixflow.module.file.error.AssetIngestError;
import com.pixflow.module.file.error.AssetIngestErrorMapper;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageMapper;
import java.time.Clock;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

public final class DefaultAssetDeletionService implements AssetDeletionService {
    private final AssetPackageMapper packageMapper;

    private final AssetImageMapper imageMapper;

    private final AssetCopyMapper copyMapper;

    private final AssetIngestErrorMapper errorMapper;

    private final AssetReferenceTombstoneMapper tombstoneMapper;

    private final AssetCleanupIntentMapper intentMapper;

    private final ObjectStorage objectStorage;

    private final TransactionTemplate transactions;

    private final Clock clock;

    public DefaultAssetDeletionService(
            AssetPackageMapper packageMapper,
            AssetImageMapper imageMapper,
            AssetCopyMapper copyMapper,
            AssetIngestErrorMapper errorMapper,
            AssetReferenceTombstoneMapper tombstoneMapper,
            AssetCleanupIntentMapper intentMapper,
            ObjectStorage objectStorage,
            TransactionTemplate transactions,
            Clock clock) {
        this.packageMapper = packageMapper;
        this.imageMapper = imageMapper;
        this.copyMapper = copyMapper;
        this.errorMapper = errorMapper;
        this.tombstoneMapper = tombstoneMapper;
        this.intentMapper = intentMapper;
        this.objectStorage = objectStorage;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void deleteImage(long packageId, long imageId) {
        AssetImage image = imageMapper.selectById(imageId);
        if (image == null) {
            return;
        }
        if (image.getPackageId() != packageId) {
            throw new IllegalArgumentException("image does not belong to package");
        }
        transactions.executeWithoutResult(status -> prepareImage(image));
        cleanup(requireIntent("IMAGE", packageId, imageId));
    }

    @Override
    public void deletePackage(long packageId) {
        AssetPackage assetPackage = packageMapper.selectById(packageId);
        if (assetPackage == null) {
            return;
        }
        transactions.executeWithoutResult(status -> preparePackage(assetPackage));
        cleanup(requireIntent("PACKAGE", packageId, 0L));
    }

    @Override
    public int resumePending(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("cleanup recovery limit out of range");
        }
        int completed = 0;
        for (AssetCleanupIntent intent : intentMapper.findPending(limit)) {
            try {
                cleanup(intent);
                completed++;
            } catch (RuntimeException ignored) {
                // 单个对象失败不能阻塞本批其他删除意图，错误已写回 intent。
            }
        }
        return completed;
    }

    private void prepareImage(AssetImage image) {
        String displayName = image.getDisplayName() == null
                ? image.getOriginalPath() == null ? "Generated Image " + image.getId() : image.getOriginalPath()
                : image.getDisplayName();
        tombstoneMapper.insertIfAbsent("IMAGE", image.getPackageId(), normalizedSku(image.getSkuId()),
                image.getId(), displayName);
        BucketType bucket = "GENERATED".equals(image.getSourceType())
                ? BucketType.valueOf(image.getStableBucket()) : BucketType.PACKAGES;
        intentMapper.insertIfAbsent("IMAGE", image.getPackageId(), image.getId(), bucket.name(),
                image.getMinioKey(), false, clock.instant());
        AssetImage update = new AssetImage();
        update.setId(image.getId());
        // intent 落库后立即从 resolver/listing 隐藏，避免删除已确认后仍参与处理。
        update.setDeletionStatus("PENDING");
        imageMapper.updateById(update);
    }

    private void preparePackage(AssetPackage assetPackage) {
        tombstoneMapper.insertIfAbsent("PACKAGE", assetPackage.getId(), "", 0L, assetPackage.getName());
        List<AssetImage> originals = imageMapper.selectList(new LambdaQueryWrapper<AssetImage>()
                .eq(AssetImage::getPackageId, assetPackage.getId())
                .eq(AssetImage::getSourceType, "ORIGINAL"));
        for (AssetImage image : originals) {
            if (image.getSkuId() != null && !image.getSkuId().isBlank()) {
                tombstoneMapper.insertIfAbsent("SKU", assetPackage.getId(), image.getSkuId(),
                        0L, image.getSkuId());
            }
            String name = image.getDisplayName() == null ? image.getOriginalPath() : image.getDisplayName();
            tombstoneMapper.insertIfAbsent("IMAGE", assetPackage.getId(), normalizedSku(image.getSkuId()),
                    image.getId(), name == null ? "Image " + image.getId() : name);
        }
        intentMapper.insertIfAbsent("PACKAGE", assetPackage.getId(), 0L, BucketType.PACKAGES.name(),
                assetPackage.getId() + "/", true, clock.instant());
        AssetPackage update = new AssetPackage();
        update.setId(assetPackage.getId());
        update.setCleanupStatus("PENDING");
        packageMapper.updateById(update);
    }

    private void cleanup(AssetCleanupIntent intent) {
        try {
            BucketType bucket = BucketType.valueOf(intent.getStorageBucket());
            if (Boolean.TRUE.equals(intent.getPrefixCleanup())) {
                objectStorage.deleteByPrefix(bucket, intent.getStorageKey());
            } else {
                objectStorage.delete(ObjectLocation.of(bucket, intent.getStorageKey()));
            }
            transactions.executeWithoutResult(status -> completeDatabaseCleanup(intent));
        } catch (RuntimeException ex) {
            intentMapper.recordFailure(intent.getId(), Sanitizer.sanitizeMessage(ex.getMessage()), clock.instant());
            throw ex;
        }
    }

    private void completeDatabaseCleanup(AssetCleanupIntent intent) {
        if ("IMAGE".equals(intent.getReferenceKind())) {
            imageMapper.deleteById(intent.getImageId());
        } else {
            long packageId = intent.getPackageId();
            imageMapper.delete(new LambdaQueryWrapper<AssetImage>()
                    .eq(AssetImage::getPackageId, packageId)
                    .eq(AssetImage::getSourceType, "ORIGINAL"));
            copyMapper.delete(new LambdaQueryWrapper<AssetCopy>().eq(AssetCopy::getPackageId, packageId));
            errorMapper.delete(new LambdaQueryWrapper<AssetIngestError>()
                    .eq(AssetIngestError::getPackageId, packageId));
            packageMapper.deleteById(packageId);
        }
        intentMapper.deleteById(intent.getId());
    }

    private AssetCleanupIntent requireIntent(String kind, long packageId, long imageId) {
        AssetCleanupIntent intent = intentMapper.selectOne(new LambdaQueryWrapper<AssetCleanupIntent>()
                .eq(AssetCleanupIntent::getReferenceKind, kind)
                .eq(AssetCleanupIntent::getPackageId, packageId)
                .eq(AssetCleanupIntent::getImageId, imageId)
                .last("limit 1"));
        if (intent == null) {
            throw new IllegalStateException("cleanup intent was not persisted");
        }
        return intent;
    }

    private static String normalizedSku(String skuId) {
        return skuId == null ? "" : skuId;
    }
}
