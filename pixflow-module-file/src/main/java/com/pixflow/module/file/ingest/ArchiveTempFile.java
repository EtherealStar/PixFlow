package com.pixflow.module.file.ingest;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.file.pkg.AssetPackage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class ArchiveTempFile {
    private ArchiveTempFile() {
    }

    static Path copy(ObjectStorage storage, AssetPackage assetPackage, String suffix) throws IOException {
        Path file = Files.createTempFile("pixflow-archive-", suffix);
        try (InputStream input = storage.getStream(
                ObjectLocation.of(BucketType.PACKAGES, assetPackage.getMinioZipKey()))) {
            Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return file;
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(file);
            throw ex;
        }
    }
}
