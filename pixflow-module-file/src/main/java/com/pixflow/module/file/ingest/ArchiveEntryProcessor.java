package com.pixflow.module.file.ingest;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageKeys;
import com.pixflow.module.file.error.AssetIngestError;
import com.pixflow.module.file.error.AssetIngestErrorMapper;
import com.pixflow.module.file.error.FileErrorCode;
import com.pixflow.module.file.error.IngestStage;
import com.pixflow.module.file.copydoc.AssetCopy;
import com.pixflow.module.file.copydoc.AssetCopyMapper;
import com.pixflow.module.file.copydoc.CopyDocParser;
import com.pixflow.module.file.copydoc.CsvCopyDocParser;
import com.pixflow.module.file.copydoc.ExcelCopyDocParser;
import com.pixflow.module.file.copydoc.ParsedCopyRow;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.naming.FileNameParser;
import com.pixflow.module.file.naming.ParsedName;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.PackageStatus;
import com.pixflow.module.file.visual.AssetImageVisualWriter;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ArchiveEntryProcessor {
    private final ObjectStorage objectStorage;

    private final AssetPackageService packageService;

    private final AssetImageVisualWriter imageWriter;

    private final AssetIngestErrorMapper errorMapper;

    private final FileNameParser fileNameParser;

    private final ImageAdmission imageAdmission;

    private final ArchiveSafetyPolicy safetyPolicy;

    private final Clock clock;

    private final AssetCopyMapper copyMapper;

    private final CsvCopyDocParser csvParser;

    private final ExcelCopyDocParser excelParser;

    public ArchiveEntryProcessor(
            ObjectStorage objectStorage,
            AssetPackageService packageService,
            AssetImageVisualWriter imageWriter,
            AssetIngestErrorMapper errorMapper,
            FileNameParser fileNameParser,
            ImageAdmission imageAdmission,
            ArchiveSafetyPolicy safetyPolicy,
            Clock clock,
            AssetCopyMapper copyMapper,
            CsvCopyDocParser csvParser,
            ExcelCopyDocParser excelParser) {
        this.objectStorage = objectStorage;
        this.packageService = packageService;
        this.imageWriter = imageWriter;
        this.errorMapper = errorMapper;
        this.fileNameParser = fileNameParser;
        this.imageAdmission = imageAdmission;
        this.safetyPolicy = safetyPolicy;
        this.clock = clock;
        this.copyMapper = copyMapper;
        this.csvParser = csvParser;
        this.excelParser = excelParser;
    }

    public Session begin(long packageId) {
        packageService.markExtracting(packageId);
        return new Session(packageId);
    }

    public long maxEntryBytes() {
        return safetyPolicy.maxEntryBytes();
    }

    public final class Session {
        private final long packageId;

        private int entries;

        private int extracted;

        private int failures;

        private long totalBytes;

        private Session(long packageId) {
            this.packageId = packageId;
        }

        public void accept(String path, byte[] bytes, long declaredSize,
                           long compressedSize, boolean encrypted) {
            packageService.require(packageId);
            entries++;
            String relativePath = safetyPolicy.admitPath(
                    path, entries, declaredSize, compressedSize, encrypted);
            totalBytes += bytes.length;
            safetyPolicy.admitActualSize(bytes.length, totalBytes);
            CopyDocParser copyDocParser = copyDocParser(relativePath);
            if (copyDocParser != null) {
                importCopyDocument(relativePath, bytes, copyDocParser);
                return;
            }
            ImageAdmission.AdmissionResult admission = imageAdmission.inspect(relativePath, bytes, bytes.length);
            if (!admission.accepted()) {
                failures++;
                recordError(relativePath, IngestStage.IMAGE_ADMISSION, admission.code(), admission.message());
                return;
            }
            try {
                storeImage(relativePath, bytes, admission.contentType());
                extracted++;
                packageService.updateProgress(packageId, entries, extracted);
            } catch (RuntimeException ex) {
                failures++;
                recordError(relativePath, IngestStage.STORAGE_UPLOAD,
                        "STORAGE_UPLOAD_FAILED", ex.getMessage());
            }
        }

        public void finish() {
            PackageStatus status = extracted == 0
                    ? PackageStatus.FAILED
                    : failures == 0 ? PackageStatus.READY : PackageStatus.PARTIAL;
            packageService.finish(packageId, status,
                    failures == 0 ? null : "ingest failures: " + failures);
            if (extracted == 0) {
                throw new PixFlowException(FileErrorCode.NO_VALID_IMAGE, "package contains no valid image");
            }
        }

        private void storeImage(String relativePath, byte[] bytes, String contentType) {
            ObjectLocation location = StorageKeys.packageImage(packageId, relativePath);
            objectStorage.put(location, new ByteArrayInputStream(bytes), bytes.length, contentType);
            try {
                packageService.require(packageId);
            } catch (RuntimeException cancelled) {
                objectStorage.delete(location);
                throw cancelled;
            }
            try {
                ParsedName parsed = fileNameParser.parse(relativePath);
                AssetImage image = new AssetImage();
            image.setPackageId(packageId);
            image.setSkuId(parsed.skuId());
            image.setGroupKey(parsed.groupKey());
            image.setViewId(parsed.viewId());
            image.setOriginalPath(relativePath);
            image.setMinioKey(location.key());
            image.setSourceType("ORIGINAL");
            image.setPublicationStatus("READY");
            image.setStableBucket("PACKAGES");
            image.setContentType(contentType);
            image.setByteSize((long) bytes.length);
            image.setContentHash(sha256(bytes));
            image.setCreatedAt(clock.instant());
            image.setUpdatedAt(image.getCreatedAt());
            // 唯一约束是归档消息重放时的最后一道幂等防线。
                imageWriter.insertOriginal(image);
            } catch (RuntimeException failure) {
                objectStorage.delete(location);
                throw failure;
            }
        }

        private String sha256(byte[] bytes) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }

        private void recordError(String path, IngestStage stage, String code, String message) {
            AssetIngestError error = new AssetIngestError();
            error.setPackageId(packageId);
            error.setOriginalPath(path);
            error.setStage(stage);
            error.setCode(code);
            error.setMessage(message);
            error.setCreatedAt(clock.instant());
            errorMapper.insert(error);
        }

        private void importCopyDocument(String path, byte[] bytes, CopyDocParser parser) {
            try {
                for (ParsedCopyRow row : parser.parse(new ByteArrayInputStream(bytes))) {
                    if (row.skuId() == null || row.skuId().isBlank()) {
                        failures++;
                        recordError(path, IngestStage.COPYDOC_PARSE, "MISSING_SKU", "missing sku_id");
                        continue;
                    }
                    AssetCopy copy = new AssetCopy();
                    copy.setPackageId(packageId);
                    copy.setSkuId(row.skuId());
                    copy.setProductName(row.productName());
                    copy.setKeywords(row.keywords());
                    copy.setDescription(row.description());
                    copyMapper.insert(copy);
                }
            } catch (IOException | RuntimeException ex) {
                failures++;
                recordError(path, IngestStage.COPYDOC_PARSE, "COPYDOC_PARSE_FAILED", ex.getMessage());
            }
        }

    }

    private CopyDocParser copyDocParser(String path) {
        if (copyMapper == null) {
            return null;
        }
        if (csvParser.supports(path)) {
            return csvParser;
        }
        return excelParser.supports(path) ? excelParser : null;
    }
}
