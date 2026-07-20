package com.pixflow.module.file.ingest;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.file.error.FileErrorCode;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.PackageStatus;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.function.LongFunction;

public final class ZipExtractor implements ArchiveExtractor {
    private final ObjectStorage objectStorage;

    private final AssetPackageService packageService;

    private final ArchiveEntryProcessor processor;

    private final LongFunction<ObjectLocation> sourceLocation;

    public ZipExtractor(ObjectStorage objectStorage,
                        AssetPackageService packageService,
                        ArchiveEntryProcessor processor) {
        this.objectStorage = objectStorage;
        this.packageService = packageService;
        this.processor = processor;
        this.sourceLocation = packageId -> {
            AssetPackage assetPackage = packageService.require(packageId);
            return ObjectLocation.of(BucketType.PACKAGES, assetPackage.getMinioZipKey());
        };
    }

    @Override
    public ArchiveFormat format() {
        return ArchiveFormat.ZIP;
    }

    @Override
    public void extract(long packageId) {
        ArchiveEntryProcessor.Session session = processor.begin(packageId);
        try (InputStream source = objectStorage.getStream(sourceLocation.apply(packageId));
             ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    session.accept(entry.getName(), readEntry(zip, processor.maxEntryBytes()), entry.getSize(),
                            entry.getCompressedSize(), false);
                }
            }
            session.finish();
        } catch (IOException ex) {
            packageService.finish(packageId, PackageStatus.FAILED, "zip parse failed");
            throw new PixFlowException(FileErrorCode.INVALID_ZIP, "invalid zip package", ex);
        }
    }

    private static byte[] readEntry(InputStream input, long limit) throws IOException {
        BoundedArchiveOutput output = new BoundedArchiveOutput(limit);
        input.transferTo(output);
        return output.toByteArray();
    }
}
