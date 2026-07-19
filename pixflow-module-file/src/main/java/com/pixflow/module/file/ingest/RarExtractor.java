package com.pixflow.module.file.ingest;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.file.error.FileErrorCode;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.PackageStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RarExtractor implements ArchiveExtractor {
    private final ObjectStorage objectStorage;

    private final AssetPackageService packageService;

    private final ArchiveEntryProcessor processor;

    public RarExtractor(ObjectStorage objectStorage,
                        AssetPackageService packageService,
                        ArchiveEntryProcessor processor) {
        this.objectStorage = objectStorage;
        this.packageService = packageService;
        this.processor = processor;
    }

    @Override
    public ArchiveFormat format() {
        return ArchiveFormat.RAR;
    }

    @Override
    public void extract(long packageId) {
        AssetPackage assetPackage = packageService.require(packageId);
        Path temporary = null;
        try {
            temporary = ArchiveTempFile.copy(objectStorage, assetPackage, ".rar");
            ArchiveEntryProcessor.Session session = processor.begin(packageId);
            try (Archive archive = new Archive(temporary.toFile())) {
                if (archive.isEncrypted()) {
                    throw new PixFlowException(FileErrorCode.INVALID_ZIP,
                            "encrypted rar package is not allowed");
                }
                FileHeader header;
                while ((header = archive.nextFileHeader()) != null) {
                    if (!header.isDirectory()) {
                        BoundedArchiveOutput output = new BoundedArchiveOutput(processor.maxEntryBytes());
                        archive.extractFile(header, output);
                        String name = header.isUnicode()
                                ? header.getFileNameW() : header.getFileNameString();
                        session.accept(name, output.toByteArray(), header.getFullUnpackSize(),
                                header.getFullPackSize(), header.isEncrypted());
                    }
                }
            }
            session.finish();
        } catch (IOException | com.github.junrar.exception.RarException ex) {
            packageService.finish(packageId, PackageStatus.FAILED, "rar parse failed");
            throw new PixFlowException(FileErrorCode.INVALID_ZIP,
                    "invalid or encrypted rar package", ex);
        } finally {
            deleteQuietly(temporary);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // 系统临时目录清理由操作系统兜底，不覆盖真实解压结果。
            }
        }
    }
}
