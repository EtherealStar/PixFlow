package com.pixflow.module.file.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pixflow.infra.mq.PublishResult;
import com.pixflow.module.file.config.FileProperties;
import com.pixflow.module.file.pkg.AssetPackage;
import com.pixflow.module.file.pkg.AssetPackageMapper;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.PackageStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;

public class PublishGapRescan {
    private final AssetPackageService packageService;
    private final ExtractionPublisher extractionPublisher;
    private final AssetPackageMapper packageMapper;
    private final Clock clock;
    private final FileProperties properties;

    public PublishGapRescan(
            AssetPackageService packageService,
            ExtractionPublisher extractionPublisher,
            AssetPackageMapper packageMapper,
            Clock clock,
            FileProperties properties) {
        this.packageService = packageService;
        this.extractionPublisher = extractionPublisher;
        this.packageMapper = packageMapper;
        this.clock = clock;
        this.properties = properties;
    }

    // 只补投递缺口，不承担解压恢复；解压恢复交给 RabbitMQ 重投和幂等写入。
    @Scheduled(fixedDelayString = "${pixflow.file.publish-gap-rescan.interval:PT1M}")
    public void rescan() {
        Instant staleBefore = clock.instant().minus(properties.getPublishGapRescan().getStaleAfter());
        List<AssetPackage> packages = packageMapper.selectList(new LambdaQueryWrapper<AssetPackage>()
                .eq(AssetPackage::getStatus, PackageStatus.UPLOADED)
                .lt(AssetPackage::getCreatedAt, staleBefore));
        for (AssetPackage assetPackage : packages) {
            PublishResult result = extractionPublisher.publish(assetPackage.getId());
            if (result.confirmed()) {
                packageService.markExtracting(assetPackage.getId());
            }
        }
    }
}
