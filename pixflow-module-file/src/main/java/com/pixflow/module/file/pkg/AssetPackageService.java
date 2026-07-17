package com.pixflow.module.file.pkg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.file.error.FileErrorCode;
import java.time.Clock;
import java.time.Instant;

public class AssetPackageService {
    private final AssetPackageMapper packageMapper;

    private final PackageReferenceChecker referenceChecker;

    private final ObjectStorage objectStorage;

    private final Clock clock;

    public AssetPackageService(
            AssetPackageMapper packageMapper,
            PackageReferenceChecker referenceChecker,
            ObjectStorage objectStorage,
            Clock clock) {
        this.packageMapper = packageMapper;
        this.referenceChecker = referenceChecker;
        this.objectStorage = objectStorage;
        this.clock = clock;
    }

    public AssetPackage createUploadingPackage(String name) {
        return createUploadingPackage(name, null);
    }

    public AssetPackage createUploadingPackage(String name, String fileHash) {
        Instant now = clock.instant();
        AssetPackage assetPackage = new AssetPackage();
        assetPackage.setName(name);
        assetPackage.setFileHash(fileHash);
        assetPackage.setStatus(PackageStatus.UPLOADED);
        assetPackage.setImageCount(0);
        assetPackage.setExtractedCount(0);
        assetPackage.setCreatedAt(now);
        assetPackage.setUpdatedAt(now);
        packageMapper.insert(assetPackage);
        return assetPackage;
    }

    public AssetPackage require(long packageId) {
        AssetPackage assetPackage = packageMapper.selectById(packageId);
        if (assetPackage == null) {
            throw new PixFlowException(FileErrorCode.PACKAGE_NOT_FOUND, "package not found: " + packageId);
        }
        return assetPackage;
    }

    public void markSourceStored(long packageId, String zipKey, String docKey) {
        AssetPackage update = new AssetPackage();
        update.setId(packageId);
        update.setMinioZipKey(zipKey);
        update.setDocKey(docKey);
        update.setUpdatedAt(clock.instant());
        packageMapper.updateById(update);
    }

    public void markExtracting(long packageId) {
        updateStatus(packageId, PackageStatus.EXTRACTING, null);
    }

    public void updateProgress(long packageId, int imageCount, int extractedCount) {
        AssetPackage update = new AssetPackage();
        update.setId(packageId);
        update.setImageCount(imageCount);
        update.setExtractedCount(extractedCount);
        update.setUpdatedAt(clock.instant());
        packageMapper.updateById(update);
    }

    public void finish(long packageId, PackageStatus status, String errorSummary) {
        updateStatus(packageId, status, errorSummary);
    }

    public void delete(long packageId) {
        require(packageId);
        if (referenceChecker.isReferenced(packageId)) {
            AssetPackage update = new AssetPackage();
            update.setId(packageId);
            update.setDeletedAt(clock.instant());
            update.setUpdatedAt(clock.instant());
            packageMapper.updateById(update);
            return;
        }
        // 物理删除必须先清对象前缀；若对象清理失败，DB 行仍保留，便于重试删除。
        objectStorage.deleteByPrefix(BucketType.PACKAGES, packageId + "/");
        packageMapper.deleteById(packageId);
    }

    public LambdaQueryWrapper<AssetPackage> visiblePackages() {
        return new LambdaQueryWrapper<AssetPackage>().isNull(AssetPackage::getDeletedAt);
    }

    private void updateStatus(long packageId, PackageStatus status, String errorSummary) {
        AssetPackage update = new AssetPackage();
        update.setId(packageId);
        update.setStatus(status);
        update.setErrorSummary(errorSummary == null ? null : Sanitizer.sanitizeMessage(errorSummary));
        update.setUpdatedAt(clock.instant());
        packageMapper.updateById(update);
    }
}
