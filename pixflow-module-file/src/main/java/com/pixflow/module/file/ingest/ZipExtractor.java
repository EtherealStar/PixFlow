package com.pixflow.module.file.ingest;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.progress.ProgressNotifier;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageKeys;
import com.pixflow.module.file.config.FileProperties;
import com.pixflow.module.file.error.AssetIngestError;
import com.pixflow.module.file.error.AssetIngestErrorMapper;
import com.pixflow.module.file.error.FileErrorCode;
import com.pixflow.module.file.error.IngestStage;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.naming.FileNameParser;
import com.pixflow.module.file.naming.ParsedName;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.PackageStatus;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZipExtractor {
    private final ObjectStorage objectStorage;

    private final AssetPackageService packageService;

    private final AssetImageMapper imageMapper;

    private final AssetIngestErrorMapper errorMapper;

    private final FileNameParser fileNameParser;

    private final ImageAdmission imageAdmission;

    private final FileProperties properties;

    private final ProgressNotifier progressNotifier;

    private final Clock clock;

    public ZipExtractor(
            ObjectStorage objectStorage,
            AssetPackageService packageService,
            AssetImageMapper imageMapper,
            AssetIngestErrorMapper errorMapper,
            FileNameParser fileNameParser,
            ImageAdmission imageAdmission,
            FileProperties properties,
            ProgressNotifier progressNotifier,
            Clock clock) {
        this.objectStorage = objectStorage;
        this.packageService = packageService;
        this.imageMapper = imageMapper;
        this.errorMapper = errorMapper;
        this.fileNameParser = fileNameParser;
        this.imageAdmission = imageAdmission;
        this.properties = properties;
        this.progressNotifier = progressNotifier;
        this.clock = clock;
    }

    public void extract(long packageId) {
        packageService.markExtracting(packageId);
        int total = 0;
        int extracted = 0;
        int failures = 0;
        try (InputStream source = objectStorage.getStream(StorageKeys.packageSource(packageId, "zip"));
             ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                total++;
                enforceZipLimits(total, entry);
                String relPath = ZipPathValidator.validate(entry.getName());
                byte[] bytes = readEntry(zip, entry);
                ImageAdmission.AdmissionResult admission =
                        imageAdmission.inspect(relPath, bytes, bytes.length);
                if (!admission.accepted()) {
                    failures++;
                    recordError(packageId, relPath, IngestStage.IMAGE_ADMISSION, admission.code(), admission.message());
                    continue;
                }
                try {
                    storeImage(packageId, relPath, bytes, admission.contentType());
                    extracted++;
                    packageService.updateProgress(packageId, total, extracted);
                    publishProgress(packageId, extracted, total, PackageStatus.EXTRACTING);
                } catch (RuntimeException ex) {
                    failures++;
                    recordError(
                            packageId,
                            relPath,
                            IngestStage.STORAGE_UPLOAD,
                            "STORAGE_UPLOAD_FAILED",
                            ex.getMessage());
                }
            }
        } catch (IOException ex) {
            packageService.finish(packageId, PackageStatus.FAILED, "zip parse failed");
            throw new PixFlowException(FileErrorCode.INVALID_ZIP, "invalid zip package", ex);
        }

        PackageStatus status = finalStatus(extracted, failures);
        packageService.finish(packageId, status, failures == 0 ? null : "ingest failures: " + failures);
        publishProgress(packageId, extracted, total, status);
        if (extracted == 0) {
            throw new PixFlowException(FileErrorCode.NO_VALID_IMAGE, "package contains no valid image");
        }
    }

    private void storeImage(long packageId, String relPath, byte[] bytes, String contentType) {
        ObjectLocation location = StorageKeys.packageImage(packageId, relPath);
        objectStorage.put(location, new ByteArrayInputStream(bytes), bytes.length, contentType);
        ParsedName parsed = fileNameParser.parse(relPath);
        AssetImage image = new AssetImage();
        image.setPackageId(packageId);
        image.setSkuId(parsed.skuId());
        image.setGroupKey(parsed.groupKey());
        image.setViewId(parsed.viewId());
        image.setOriginalPath(relPath);
        image.setMinioKey(location.key());
        image.setCreatedAt(clock.instant());
        // DB 唯一约束负责最终幂等；这里保持普通 insert，后续可替换为 ON DUPLICATE SQL。
        imageMapper.insert(image);
    }

    private void enforceZipLimits(int total, ZipEntry entry) {
        if (total > properties.getZip().getMaxEntries()) {
            throw new PixFlowException(FileErrorCode.ZIP_BOMB_DETECTED, "zip entry count exceeds limit");
        }
        long size = entry.getSize();
        if (size > properties.getZip().getMaxEntryBytes().toBytes()) {
            throw new PixFlowException(FileErrorCode.ZIP_BOMB_DETECTED, "zip entry size exceeds limit");
        }
        long compressedSize = Math.max(1, entry.getCompressedSize());
        if (size > 0 && size / compressedSize > properties.getZip().getMaxCompressionRatio()) {
            throw new PixFlowException(FileErrorCode.ZIP_BOMB_DETECTED, "zip entry compression ratio exceeds limit");
        }
    }

    private byte[] readEntry(ZipInputStream zip, ZipEntry entry) throws IOException {
        long maxEntryBytes = properties.getZip().getMaxEntryBytes().toBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = zip.read(buffer)) >= 0) {
            total += read;
            if (total > maxEntryBytes) {
                throw new PixFlowException(FileErrorCode.ZIP_BOMB_DETECTED, "zip entry actual size exceeds limit");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private void recordError(long packageId, String path, IngestStage stage, String code, String message) {
        AssetIngestError error = new AssetIngestError();
        error.setPackageId(packageId);
        error.setOriginalPath(path);
        error.setStage(stage);
        error.setCode(code);
        error.setMessage(message);
        error.setCreatedAt(clock.instant());
        errorMapper.insert(error);
    }

    private void publishProgress(long packageId, int extracted, int total, PackageStatus status) {
        progressNotifier.publish(
                "packages/" + packageId + "/progress",
                new ExtractionProgress(packageId, extracted, total, status, null));
    }

    private static PackageStatus finalStatus(int extracted, int failures) {
        if (extracted == 0) {
            return PackageStatus.FAILED;
        }
        return failures == 0 ? PackageStatus.READY : PackageStatus.PARTIAL;
    }
}
