package com.pixflow.module.file.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.progress.ProgressNotifier;
import com.pixflow.infra.storage.BucketType;
import com.pixflow.infra.storage.ObjectRef;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.StorageKeys;
import com.pixflow.module.file.config.FileProperties;
import com.pixflow.module.file.error.AssetIngestError;
import com.pixflow.module.file.error.AssetIngestErrorMapper;
import com.pixflow.module.file.image.AssetImage;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.naming.DefaultSkuExtractor;
import com.pixflow.module.file.naming.FileNameParser;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.PackageStatus;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ZipExtractorTest {
    private static final Instant NOW = Instant.parse("2026-06-28T12:00:00Z");
    private final ObjectStorage objectStorage = org.mockito.Mockito.mock(ObjectStorage.class);
    private final AssetPackageService packageService = org.mockito.Mockito.mock(AssetPackageService.class);
    private final AssetImageMapper imageMapper = org.mockito.Mockito.mock(AssetImageMapper.class);
    private final AssetIngestErrorMapper errorMapper = org.mockito.Mockito.mock(AssetIngestErrorMapper.class);
    private final RecordingProgressNotifier progressNotifier = new RecordingProgressNotifier();
    private final FileProperties properties = new FileProperties();
    private final ZipExtractor extractor = new ZipExtractor(
            objectStorage,
            packageService,
            imageMapper,
            errorMapper,
            new FileNameParser(new DefaultSkuExtractor()),
            new ImageAdmission(properties),
            properties,
            progressNotifier,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void validImagesAreStoredInsertedAndReportedReady() {
        when(objectStorage.getStream(StorageKeys.packageSource(99L, "zip"))).thenReturn(zipStream(
                entry("G1_SKU9_FRONT.png", pngBytes()),
                entry("SKU8_SIDE.jpg", jpgBytes())));
        when(objectStorage.put(any(), any(InputStream.class), any(Long.class), any()))
                .thenReturn(new ObjectRef(BucketType.PACKAGES, "99/images/image.png", 8, "etag"));

        extractor.extract(99L);

        ArgumentCaptor<AssetImage> imageCaptor = ArgumentCaptor.forClass(AssetImage.class);
        verify(imageMapper, org.mockito.Mockito.times(2)).insert(imageCaptor.capture());
        assertThat(imageCaptor.getAllValues())
                .extracting(AssetImage::getOriginalPath)
                .containsExactly("G1_SKU9_FRONT.png", "SKU8_SIDE.jpg");
        assertThat(imageCaptor.getAllValues().get(0).getGroupKey()).isEqualTo("G1");
        assertThat(imageCaptor.getAllValues().get(0).getSkuId()).isEqualTo("SKU9");
        assertThat(imageCaptor.getAllValues().get(0).getViewId()).isEqualTo("FRONT");
        verify(packageService).updateProgress(99L, 1, 1);
        verify(packageService).updateProgress(99L, 2, 2);
        verify(packageService).finish(99L, PackageStatus.READY, null);
        assertThat(progressNotifier.events)
                .extracting(event -> event.status())
                .containsExactly(PackageStatus.EXTRACTING, PackageStatus.EXTRACTING, PackageStatus.READY);
    }

    @Test
    void invalidImageIsRecordedAndPackageEndsPartialWhenAnotherImageSucceeds() {
        when(objectStorage.getStream(StorageKeys.packageSource(99L, "zip"))).thenReturn(zipStream(
                entry("SKU9_FRONT.png", pngBytes()),
                entry("SKU9_BAD.jpg", "not an image".getBytes())));
        when(objectStorage.put(any(), any(InputStream.class), any(Long.class), any()))
                .thenReturn(new ObjectRef(BucketType.PACKAGES, "99/images/image.png", 8, "etag"));

        extractor.extract(99L);

        verify(imageMapper).insert(any(AssetImage.class));
        ArgumentCaptor<AssetIngestError> errorCaptor = ArgumentCaptor.forClass(AssetIngestError.class);
        verify(errorMapper).insert(errorCaptor.capture());
        assertThat(errorCaptor.getValue().getOriginalPath()).isEqualTo("SKU9_BAD.jpg");
        assertThat(errorCaptor.getValue().getCode()).isEqualTo("UNSUPPORTED_IMAGE_FORMAT");
        verify(packageService).finish(99L, PackageStatus.PARTIAL, "ingest failures: 1");
        assertThat(progressNotifier.events.get(progressNotifier.events.size() - 1).status())
                .isEqualTo(PackageStatus.PARTIAL);
    }

    @Test
    void packageWithNoValidImagesFinishesFailedAndThrowsDomainException() {
        when(objectStorage.getStream(StorageKeys.packageSource(99L, "zip"))).thenReturn(zipStream(
                entry("SKU9_BAD.jpg", "not an image".getBytes())));

        assertThatThrownBy(() -> extractor.extract(99L))
                .isInstanceOf(PixFlowException.class)
                .hasMessageContaining("no valid image");

        verify(packageService).finish(99L, PackageStatus.FAILED, "ingest failures: 1");
        assertThat(progressNotifier.events.get(0).status()).isEqualTo(PackageStatus.FAILED);
    }

    private static ByteArrayInputStream zipStream(TestEntry... entries) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (TestEntry entry : entries) {
                    zip.putNextEntry(new ZipEntry(entry.name()));
                    zip.write(entry.bytes());
                    zip.closeEntry();
                }
            }
            return new ByteArrayInputStream(out.toByteArray());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static TestEntry entry(String name, byte[] bytes) {
        return new TestEntry(name, bytes);
    }

    private static byte[] pngBytes() {
        return new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    }

    private static byte[] jpgBytes() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
    }

    private record TestEntry(String name, byte[] bytes) {
    }

    private static class RecordingProgressNotifier implements ProgressNotifier {
        private final List<ExtractionProgress> events = new ArrayList<>();

        @Override
        public void publish(String channel, Object event) {
            assertThat(channel).isEqualTo("packages/99/progress");
            events.add((ExtractionProgress) event);
        }
    }
}
