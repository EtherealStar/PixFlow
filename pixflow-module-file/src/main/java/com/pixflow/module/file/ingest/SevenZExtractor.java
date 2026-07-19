package com.pixflow.module.file.ingest;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.file.error.FileErrorCode;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.PackageStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

public final class SevenZExtractor implements ArchiveExtractor {
    private final ObjectStorage objectStorage;

    private final AssetPackageService packageService;

    private final ArchiveEntryProcessor processor;

    public SevenZExtractor(ObjectStorage objectStorage,
                           AssetPackageService packageService,
                           ArchiveEntryProcessor processor) {
        this.objectStorage = objectStorage;
        this.packageService = packageService;
        this.processor = processor;
    }

    @Override
    public ArchiveFormat format() {
        return ArchiveFormat.SEVEN_Z;
    }

    @Override
    public void extract(long packageId) {
        AssetPackage assetPackage = packageService.require(packageId);
        Path temporary = null;
        try {
            temporary = ArchiveTempFile.copy(objectStorage, assetPackage, ".7z");
            ArchiveEntryProcessor.Session session = processor.begin(packageId);
            try (SevenZFile archive = new SevenZFile(temporary.toFile())) {
                SevenZArchiveEntry entry;
                while ((entry = archive.getNextEntry()) != null) {
                    if (!entry.isDirectory()) {
                        BoundedArchiveOutput output = new BoundedArchiveOutput(processor.maxEntryBytes());
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = archive.read(buffer)) >= 0) {
                            output.write(buffer, 0, read);
                        }
                        session.accept(entry.getName(), output.toByteArray(), entry.getSize(),
                                Math.max(1L, entry.getSize()), false);
                    }
                }
            }
            session.finish();
        } catch (IOException ex) {
            packageService.finish(packageId, PackageStatus.FAILED, "7z parse failed");
            throw new PixFlowException(FileErrorCode.INVALID_ZIP,
                    "invalid or encrypted 7z package", ex);
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
