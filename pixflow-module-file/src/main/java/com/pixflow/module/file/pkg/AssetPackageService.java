package com.pixflow.module.file.pkg;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.sanitize.Sanitizer;
import com.pixflow.module.file.error.FileErrorCode;
import java.time.Clock;
import java.time.Instant;

public class AssetPackageService {
    private final AssetPackageMapper packageMapper;

    private final Clock clock;

    public AssetPackageService(
            AssetPackageMapper packageMapper,
            Clock clock) {
        this.packageMapper = packageMapper;
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
        if (assetPackage == null || assetPackage.getCleanupStatus() != null) {
            throw new PixFlowException(FileErrorCode.PACKAGE_NOT_FOUND, "package not found: " + packageId);
        }
        return assetPackage;
    }

    public void markSourceStored(long packageId, String zipKey, String docKey) {
        markSourceStored(packageId, zipKey, docKey, null);
    }

    public void markSourceStored(long packageId, String sourceKey, String docKey, String archiveFormat) {
        AssetPackage update = new AssetPackage();
        update.setId(packageId);
        update.setMinioZipKey(sourceKey);
        update.setDocKey(docKey);
        update.setArchiveFormat(archiveFormat);
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
        int updated = packageMapper.finishExtraction(packageId, status.name(),
                errorSummary == null ? null : Sanitizer.sanitizeMessage(errorSummary), clock.instant());
        if (updated == 0) {
            require(packageId);
            throw new PixFlowException(FileErrorCode.PACKAGE_ALREADY_REFERENCED,
                    "extraction terminal state already decided");
        }
    }

    public LambdaQueryWrapper<AssetPackage> visiblePackages() {
        return new LambdaQueryWrapper<AssetPackage>()
                .isNull(AssetPackage::getCleanupStatus);
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
