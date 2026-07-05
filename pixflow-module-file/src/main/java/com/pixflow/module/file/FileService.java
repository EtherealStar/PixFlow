package com.pixflow.module.file;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.web.PageResponse;
import com.pixflow.infra.mq.PublishResult;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageKeys;
import com.pixflow.infra.storage.StoredObjectMetadata;
import com.pixflow.module.file.copydoc.AssetCopy;
import com.pixflow.module.file.copydoc.AssetCopyMapper;
import com.pixflow.module.file.copydoc.CsvCopyDocParser;
import com.pixflow.module.file.copydoc.ExcelCopyDocParser;
import com.pixflow.module.file.copydoc.CopyDocParser;
import com.pixflow.module.file.copydoc.ParsedCopyRow;
import com.pixflow.module.file.error.AssetIngestError;
import com.pixflow.module.file.error.AssetIngestErrorMapper;
import com.pixflow.module.file.error.FileErrorCode;
import com.pixflow.module.file.error.IngestStage;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.image.AssetImageView;
import com.pixflow.module.file.ingest.ExtractionPublisher;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageMapper;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.PackageStatus;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.web.multipart.MultipartFile;

public class FileService {
    private static final Duration IMAGE_PREVIEW_TTL = Duration.ofMinutes(15);

    private final AssetPackageService packageService;
    private final AssetPackageMapper packageMapper;
    private final AssetImageMapper imageMapper;
    private final AssetIngestErrorMapper errorMapper;
    private final AssetCopyMapper copyMapper;
    private final CsvCopyDocParser csvCopyDocParser;
    private final ExcelCopyDocParser excelCopyDocParser;
    private final ObjectStorage objectStorage;
    private final ExtractionPublisher extractionPublisher;
    private final Clock clock;

    public FileService(
            AssetPackageService packageService,
            AssetPackageMapper packageMapper,
            AssetImageMapper imageMapper,
            AssetIngestErrorMapper errorMapper,
            AssetCopyMapper copyMapper,
            CsvCopyDocParser csvCopyDocParser,
            ExcelCopyDocParser excelCopyDocParser,
            ObjectStorage objectStorage,
            ExtractionPublisher extractionPublisher,
            Clock clock) {
        this.packageService = packageService;
        this.packageMapper = packageMapper;
        this.imageMapper = imageMapper;
        this.errorMapper = errorMapper;
        this.copyMapper = copyMapper;
        this.csvCopyDocParser = csvCopyDocParser;
        this.excelCopyDocParser = excelCopyDocParser;
        this.objectStorage = objectStorage;
        this.extractionPublisher = extractionPublisher;
        this.clock = clock;
    }

    public UploadPackageResponse upload(MultipartFile zip, MultipartFile doc) throws IOException {
        AssetPackage assetPackage = packageService.createUploadingPackage(zip.getOriginalFilename());
        long packageId = assetPackage.getId();
        ObjectLocation zipLocation = StorageKeys.packageSource(packageId);
        objectStorage.put(zipLocation, zip.getInputStream(), zip.getSize(), contentType(zip, "application/zip"));

        String docKey = null;
        if (doc != null && !doc.isEmpty()) {
            ObjectLocation docLocation = StorageKeys.packageDoc(packageId, doc.getOriginalFilename());
            objectStorage.put(docLocation, doc.getInputStream(), doc.getSize(), contentType(doc, "application/octet-stream"));
            docKey = docLocation.key();
        }
        packageService.markSourceStored(packageId, zipLocation.key(), docKey);
        if (doc != null && !doc.isEmpty()) {
            importCopyDoc(packageId, doc);
        }
        PublishResult result = extractionPublisher.publish(packageId);
        return new UploadPackageResponse(packageId, assetPackage.getStatus(), result.confirmed());
    }

    public AssetPackage detail(long packageId) {
        return packageService.require(packageId);
    }

    public PageResponse<AssetPackage> list(long page, long size) {
        IPage<AssetPackage> result = packageMapper.selectPage(new Page<>(page, size), packageService.visiblePackages());
        return new PageResponse<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    public PageResponse<AssetIngestError> errors(long packageId, long page, long size) {
        packageService.require(packageId);
        IPage<AssetIngestError> result = errorMapper.selectPage(
                new Page<>(page, size),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AssetIngestError>()
                        .eq(AssetIngestError::getPackageId, packageId)
                        .orderByDesc(AssetIngestError::getCreatedAt));
        return new PageResponse<>(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    public PageResponse<AssetImageView> images(long packageId, long page, long size) {
        packageService.require(packageId);
        IPage<AssetImage> result = imageMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<AssetImage>()
                        .eq(AssetImage::getPackageId, packageId)
                        .isNull(AssetImage::getDeletedAt)
                        .orderByAsc(AssetImage::getId));
        List<AssetImageView> views = result.getRecords().stream().map(this::toView).toList();
        return new PageResponse<>(views, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public void deleteImage(long packageId, long imageId) {
        AssetImage image = requireImage(packageId, imageId);
        if (image.getDeletedAt() != null) {
            return;
        }
        AssetImage update = new AssetImage();
        update.setId(image.getId());
        // 单图删除只隐藏数据库事实，不删除 MinIO 对象，避免破坏历史任务回放。
        update.setDeletedAt(clock.instant());
        imageMapper.updateById(update);
    }

    public AssetImageView renameImage(long packageId, long imageId, String displayName) {
        AssetImage image = requireImage(packageId, imageId);
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
        packageService.delete(packageId);
    }

    private void importCopyDoc(long packageId, MultipartFile doc) throws IOException {
        String fileName = doc.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            return;
        }
        CopyDocParser parser = parserFor(fileName);
        if (parser == null) {
            return;
        }
        try (InputStream inputStream = doc.getInputStream()) {
            List<ParsedCopyRow> rows = parser.parse(inputStream);
            boolean hasFailure = false;
            for (ParsedCopyRow row : rows) {
                if (row.skuId() == null || row.skuId().isBlank()) {
                    // 文案缺 sku_id 只记失败明细，不让整包上传直接失败。
                    recordCopyDocError(packageId, row.rowNumber(), "COPYDOC_PARSE", "SKIP", "missing sku_id");
                    hasFailure = true;
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
            // 文案只影响辅助信息，不阻断图片入库；只要出现跳过行，就把包标成 PARTIAL。
            if (hasFailure) {
                packageService.finish(packageId, PackageStatus.PARTIAL, "copy doc contains skipped rows");
            }
        } catch (IOException ex) {
            recordCopyDocError(packageId, null, "COPYDOC_PARSE", "SKIP", ex.getMessage());
            packageService.finish(packageId, PackageStatus.PARTIAL, ex.getMessage());
        }
    }

    private CopyDocParser parserFor(String fileName) {
        if (csvCopyDocParser.supports(fileName)) {
            return csvCopyDocParser;
        }
        if (excelCopyDocParser.supports(fileName)) {
            return excelCopyDocParser;
        }
        return null;
    }

    private static String contentType(MultipartFile file, String fallback) {
        return file.getContentType() == null ? fallback : file.getContentType();
    }

    private void recordCopyDocError(long packageId, Integer rowNumber, String stage, String code, String message) {
        AssetIngestError error = new AssetIngestError();
        error.setPackageId(packageId);
        error.setOriginalPath(rowNumber == null ? null : "row-" + rowNumber);
        error.setStage(IngestStage.COPYDOC_PARSE);
        error.setCode(code);
        error.setMessage(message);
        errorMapper.insert(error);
    }

    private AssetImage requireImage(long packageId, long imageId) {
        packageService.require(packageId);
        AssetImage image = imageMapper.selectOne(new LambdaQueryWrapper<AssetImage>()
                .eq(AssetImage::getPackageId, packageId)
                .eq(AssetImage::getId, imageId)
                .last("limit 1"));
        if (image == null) {
            throw new PixFlowException(FileErrorCode.ASSET_IMAGE_NOT_FOUND,
                    "asset image not found: " + imageId);
        }
        return image;
    }

    private AssetImageView toView(AssetImage image) {
        ObjectLocation location = ObjectLocation.of(BucketType.PACKAGES, image.getMinioKey());
        URL url = objectStorage.presignGet(location, IMAGE_PREVIEW_TTL);
        return new AssetImageView(
                String.valueOf(image.getId()),
                image.getPackageId(),
                filename(image),
                image.getDisplayName(),
                image.getOriginalPath(),
                image.getSkuId(),
                image.getGroupKey(),
                image.getViewId(),
                objectSize(location),
                url,
                image.getCreatedAt());
    }

    private Long objectSize(ObjectLocation location) {
        try {
            StoredObjectMetadata metadata = objectStorage.stat(location);
            return metadata == null ? null : metadata.size();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String filename(AssetImage image) {
        String displayName = image.getDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        String path = image.getOriginalPath() == null ? image.getMinioKey() : image.getOriginalPath();
        int index = path == null ? -1 : Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return index < 0 ? path : path.substring(index + 1);
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
