package com.pixflow.module.file.ingest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.pixflow.infra.mq.PublishResult;
import com.pixflow.module.file.config.FileProperties;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageMapper;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.PackageStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublishGapRescanTest {
    private static final Instant NOW = Instant.parse("2026-06-28T12:00:00Z");
    private final AssetPackageService packageService = org.mockito.Mockito.mock(AssetPackageService.class);
    private final ExtractionPublisher extractionPublisher = org.mockito.Mockito.mock(ExtractionPublisher.class);
    private final AssetPackageMapper packageMapper = org.mockito.Mockito.mock(AssetPackageMapper.class);
    private final FileProperties properties = new FileProperties();
    private final PublishGapRescan rescan = new PublishGapRescan(
            packageService,
            extractionPublisher,
            packageMapper,
            Clock.fixed(NOW, ZoneOffset.UTC),
            properties);

    @Test
    void marksPackageExtractingOnlyWhenRepublishIsConfirmed() {
        AssetPackage confirmed = packageRow(1L);
        AssetPackage failed = packageRow(2L);
        when(packageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(confirmed, failed));
        when(extractionPublisher.publish(1L)).thenReturn(PublishResult.confirmed("pixflow.file", "file.extract", "c1"));
        when(extractionPublisher.publish(2L)).thenReturn(PublishResult.failed(
                "pixflow.file",
                "file.extract",
                "c2",
                new com.pixflow.infra.mq.PublishFailure(
                        com.pixflow.infra.mq.PublishFailureType.CONFIRM_TIMEOUT,
                        "timeout",
                        null,
                        null,
                        null)));

        rescan.rescan();

        verify(extractionPublisher).publish(1L);
        verify(extractionPublisher).publish(2L);
        verify(packageService).markExtracting(1L);
        verify(packageService, never()).markExtracting(2L);
    }

    private static AssetPackage packageRow(long id) {
        AssetPackage assetPackage = new AssetPackage();
        assetPackage.setId(id);
        assetPackage.setStatus(PackageStatus.UPLOADED);
        return assetPackage;
    }
}
