package com.pixflow.module.file.ingest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectLocation;
import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.file.config.FileProperties;
import com.pixflow.module.file.error.AssetIngestErrorMapper;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.visual.AssetImageVisualWriter;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.naming.DefaultSkuExtractor;
import com.pixflow.module.file.naming.FileNameParser;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.PackageStatus;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.junit.jupiter.api.Test;

class SevenZExtractorTest {
    @Test
    void extractsARealSevenZArchiveThroughSharedAdmissionPipeline() throws Exception {
        ObjectStorage storage = mock(ObjectStorage.class);
        AssetPackageService packages = mock(AssetPackageService.class);
        AssetImageVisualWriter images = mock(AssetImageVisualWriter.class);
        AssetIngestErrorMapper errors = mock(AssetIngestErrorMapper.class);
        FileProperties properties = new FileProperties();
        AssetPackage assetPackage = new AssetPackage();
        assetPackage.setId(99L);
        assetPackage.setMinioZipKey("99/source.7z");
        when(packages.require(99L)).thenReturn(assetPackage);
        byte[] archiveBytes = sevenZBytes();
        when(storage.getStream(ObjectLocation.of(BucketType.PACKAGES, "99/source.7z")))
                .thenReturn(new ByteArrayInputStream(archiveBytes));
        when(storage.put(any(), any(InputStream.class), any(Long.class), any()))
                .thenReturn(new ObjectRef(BucketType.PACKAGES, "99/images/front.png", 11, "etag"));
        ArchiveEntryProcessor processor = new ArchiveEntryProcessor(
                storage, packages, images, errors,
                new FileNameParser(new DefaultSkuExtractor()), new ImageAdmission(properties),
                new ArchiveSafetyPolicy(properties), Clock.systemUTC(),
                null, null, null);
        SevenZExtractor extractor = new SevenZExtractor(storage, packages, processor);

        extractor.extract(99L);

        verify(images).insertOriginal(any(AssetImage.class));
        verify(packages).finish(99L, PackageStatus.READY, null);
    }

    private static byte[] sevenZBytes() throws Exception {
        Path file = Files.createTempFile("pixflow-seven-z-test", ".7z");
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        try (SevenZOutputFile archive = new SevenZOutputFile(file.toFile())) {
            SevenZArchiveEntry entry = new SevenZArchiveEntry();
            entry.setName("SKU-1_FRONT.png");
            entry.setSize(png.length);
            archive.putArchiveEntry(entry);
            archive.write(png);
            archive.closeArchiveEntry();
        }
        try {
            return Files.readAllBytes(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
