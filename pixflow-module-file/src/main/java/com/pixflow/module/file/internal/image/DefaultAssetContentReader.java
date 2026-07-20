package com.pixflow.module.file.internal.image;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.contracts.asset.ImageAssetReferenceKey;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.file.api.AssetContentMetadata;
import com.pixflow.module.file.api.AssetContentReader;
import com.pixflow.module.file.api.AssetSourceType;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.List;

public final class DefaultAssetContentReader implements AssetContentReader {
    private final CanonicalAssetReferenceCodec codec;

    private final AssetImageMapper imageMapper;

    private final ObjectStorage objectStorage;

    public DefaultAssetContentReader(CanonicalAssetReferenceCodec codec,
                                     AssetImageMapper imageMapper,
                                     ObjectStorage objectStorage) {
        this.codec = codec;
        this.imageMapper = imageMapper;
        this.objectStorage = objectStorage;
    }

    @Override
    public AssetContentMetadata require(String referenceKey) {
        LocatedImage located = locate(referenceKey);
        return metadata(referenceKey, located);
    }

    @Override
    public AssetContentMetadata require(long packageId, long imageId) {
        String referenceKey = codec.serialize(new ImageAssetReferenceKey(packageId, imageId));
        return require(referenceKey);
    }

    @Override
    public List<AssetContentMetadata> listCurrentOriginals(long packageId) {
        return list(packageId, AssetSourceType.ORIGINAL);
    }

    @Override
    public List<AssetContentMetadata> listReady(long packageId) {
        return list(packageId, null);
    }

    private List<AssetContentMetadata> list(long packageId, AssetSourceType sourceType) {
        LambdaQueryWrapper<AssetImage> query = new LambdaQueryWrapper<AssetImage>()
                .eq(AssetImage::getPackageId, packageId)
                .eq(AssetImage::getPublicationStatus, "READY")
                .isNull(AssetImage::getDeletionStatus)
                .orderByAsc(AssetImage::getId);
        if (sourceType != null) {
            query.eq(AssetImage::getSourceType, sourceType.name());
        }
        return imageMapper.selectList(query)
                .stream()
                .map(image -> require(packageId, image.getId()))
                .toList();
    }

    @Override
    public InputStream open(String referenceKey) {
        return objectStorage.getStream(locate(referenceKey).location());
    }

    @Override
    public URL presign(String referenceKey, Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("presign ttl must be positive");
        }
        return objectStorage.presignGet(locate(referenceKey).location(), ttl);
    }

    private LocatedImage locate(String referenceKey) {
        if (!(codec.parse(referenceKey) instanceof ImageAssetReferenceKey key)) {
            throw new IllegalArgumentException("referenceKey must identify an image");
        }
        AssetImage image = imageMapper.selectById(key.imageId());
        if (image == null || image.getPackageId() != key.packageId()
                || image.getDeletionStatus() != null
                || !"READY".equals(image.getPublicationStatus())) {
            throw new IllegalArgumentException("READY asset image not found");
        }
        AssetSourceType sourceType = AssetSourceType.valueOf(image.getSourceType());
        BucketType bucket = sourceType == AssetSourceType.GENERATED
                ? BucketType.valueOf(image.getStableBucket()) : BucketType.PACKAGES;
        return new LocatedImage(image, ObjectLocation.of(bucket, image.getMinioKey()));
    }

    private AssetContentMetadata metadata(String referenceKey, LocatedImage located) {
        AssetImage image = located.image();
        long size = image.getByteSize() == null ? objectStorage.stat(located.location()).size()
                : image.getByteSize();
        return new AssetContentMetadata(referenceKey, image.getPackageId(), image.getId(), image.getSkuId(),
                image.getGroupKey(), image.getViewId(), AssetSourceType.valueOf(image.getSourceType()),
                image.getContentType(), image.getContentHash(), size);
    }

    private record LocatedImage(AssetImage image, ObjectLocation location) {
    }
}
