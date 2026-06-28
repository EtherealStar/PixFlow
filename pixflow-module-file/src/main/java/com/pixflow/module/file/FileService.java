package com.pixflow.module.file;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pixflow.common.web.PageResponse;
import com.pixflow.infra.mq.PublishResult;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageKeys;
import com.pixflow.module.file.copydoc.AssetCopy;
import com.pixflow.module.file.copydoc.AssetCopyMapper;
import com.pixflow.module.file.copydoc.CsvCopyDocParser;
import com.pixflow.module.file.copydoc.ExcelCopyDocParser;
import com.pixflow.module.file.copydoc.CopyDocParser;
import com.pixflow.module.file.copydoc.ParsedCopyRow;
import com.pixflow.module.file.error.AssetIngestError;
import com.pixflow.module.file.error.AssetIngestErrorMapper;
import com.pixflow.module.file.error.IngestStage;
import com.pixflow.module.file.ingest.ExtractionPublisher;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageMapper;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.PackageStatus;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public class FileService {
    private final AssetPackageService packageService;
    private final AssetPackageMapper packageMapper;
    private final AssetIngestErrorMapper errorMapper;
    private final AssetCopyMapper copyMapper;
    private final CsvCopyDocParser csvCopyDocParser;
    private final ExcelCopyDocParser excelCopyDocParser;
    private final ObjectStorage objectStorage;
    private final ExtractionPublisher extractionPublisher;

    public FileService(
            AssetPackageService packageService,
            AssetPackageMapper packageMapper,
            AssetIngestErrorMapper errorMapper,
            AssetCopyMapper copyMapper,
            CsvCopyDocParser csvCopyDocParser,
            ExcelCopyDocParser excelCopyDocParser,
            ObjectStorage objectStorage,
            ExtractionPublisher extractionPublisher) {
        this.packageService = packageService;
        this.packageMapper = packageMapper;
        this.errorMapper = errorMapper;
        this.copyMapper = copyMapper;
        this.csvCopyDocParser = csvCopyDocParser;
        this.excelCopyDocParser = excelCopyDocParser;
        this.objectStorage = objectStorage;
        this.extractionPublisher = extractionPublisher;
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
}
