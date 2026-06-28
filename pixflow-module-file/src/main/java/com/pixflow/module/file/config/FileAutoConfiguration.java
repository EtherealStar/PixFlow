package com.pixflow.module.file.config;

import com.pixflow.common.progress.ProgressNotifier;
import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.consumer.ManagedListenerContainerFactory;
import com.pixflow.infra.mq.topology.TopologyRegistrar;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.module.file.FileService;
import com.pixflow.module.file.copydoc.AssetCopyMapper;
import com.pixflow.module.file.copydoc.CopyDocParser;
import com.pixflow.module.file.copydoc.CsvCopyDocParser;
import com.pixflow.module.file.copydoc.ExcelCopyDocParser;
import com.pixflow.module.file.error.AssetIngestErrorMapper;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.ingest.ExtractionConsumer;
import com.pixflow.module.file.ingest.ExtractionErrorHandler;
import com.pixflow.module.file.ingest.ExtractionMessage;
import com.pixflow.module.file.ingest.ExtractionPublisher;
import com.pixflow.module.file.ingest.ExtractionTopology;
import com.pixflow.module.file.ingest.ImageAdmission;
import com.pixflow.module.file.ingest.PublishGapRescan;
import com.pixflow.module.file.ingest.ZipExtractor;
import com.pixflow.module.file.naming.DefaultSkuExtractor;
import com.pixflow.module.file.naming.FileNameParser;
import com.pixflow.module.file.naming.SkuExtractor;
import com.pixflow.module.file.pkg.AssetPackageMapper;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.DefaultPackageReferenceChecker;
import com.pixflow.module.file.pkg.PackageReferenceChecker;
import java.time.Clock;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.mybatis.spring.annotation.MapperScan;

@AutoConfiguration
@EnableConfigurationProperties(FileProperties.class)
@EnableScheduling
@MapperScan("com.pixflow.module.file")
public class FileAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock fileClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProgressNotifier noopProgressNotifier() {
        return (channel, event) -> {
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public SkuExtractor skuExtractor() {
        return new DefaultSkuExtractor();
    }

    @Bean
    @ConditionalOnMissingBean
    public FileNameParser fileNameParser(SkuExtractor skuExtractor) {
        return new FileNameParser(skuExtractor);
    }

    @Bean
    @ConditionalOnMissingBean
    public ImageAdmission imageAdmission(FileProperties properties) {
        return new ImageAdmission(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public CsvCopyDocParser csvCopyDocParser(FileProperties properties) {
        return new CsvCopyDocParser(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExcelCopyDocParser excelCopyDocParser(FileProperties properties) {
        return new ExcelCopyDocParser(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public PackageReferenceChecker packageReferenceChecker() {
        return new DefaultPackageReferenceChecker();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({AssetPackageMapper.class, ObjectStorage.class})
    public AssetPackageService assetPackageService(
            AssetPackageMapper packageMapper,
            PackageReferenceChecker referenceChecker,
            ObjectStorage objectStorage,
            Clock clock) {
        return new AssetPackageService(packageMapper, referenceChecker, objectStorage, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MessagePublisher.class)
    public ExtractionPublisher extractionPublisher(MessagePublisher messagePublisher) {
        return new ExtractionPublisher(messagePublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({AssetPackageService.class, AssetPackageMapper.class, AssetIngestErrorMapper.class, ObjectStorage.class, ExtractionPublisher.class})
    public FileService fileService(
            AssetPackageService packageService,
            AssetPackageMapper packageMapper,
            AssetIngestErrorMapper errorMapper,
            AssetCopyMapper copyMapper,
            CsvCopyDocParser csvCopyDocParser,
            ExcelCopyDocParser excelCopyDocParser,
            ObjectStorage objectStorage,
            ExtractionPublisher extractionPublisher) {
        return new FileService(
                packageService,
                packageMapper,
                errorMapper,
                copyMapper,
                csvCopyDocParser,
                excelCopyDocParser,
                objectStorage,
                extractionPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ObjectStorage.class, AssetPackageService.class, AssetImageMapper.class, AssetIngestErrorMapper.class})
    public ZipExtractor zipExtractor(
            ObjectStorage objectStorage,
            AssetPackageService packageService,
            AssetImageMapper imageMapper,
            AssetIngestErrorMapper errorMapper,
            FileNameParser fileNameParser,
            ImageAdmission imageAdmission,
            FileProperties properties,
            ProgressNotifier progressNotifier,
            Clock clock) {
        return new ZipExtractor(
                objectStorage,
                packageService,
                imageMapper,
                errorMapper,
                fileNameParser,
                imageAdmission,
                properties,
                progressNotifier,
                clock);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ZipExtractor.class)
    public ExtractionConsumer extractionConsumer(ZipExtractor zipExtractor) {
        return new ExtractionConsumer(zipExtractor);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExtractionErrorHandler extractionErrorHandler() {
        return new ExtractionErrorHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({AssetPackageService.class, AssetPackageMapper.class, ExtractionPublisher.class})
    public PublishGapRescan publishGapRescan(
            AssetPackageService packageService,
            AssetPackageMapper packageMapper,
            ExtractionPublisher extractionPublisher,
            FileProperties properties,
            Clock clock) {
        return new PublishGapRescan(packageService, extractionPublisher, packageMapper, clock, properties);
    }

    @Bean
    @ConditionalOnBean(TopologyRegistrar.class)
    public Object fileExtractionTopologyRegistration(TopologyRegistrar registrar) {
        registrar.register(ExtractionTopology.topology());
        return new Object();
    }

    @Bean
    @ConditionalOnBean({ManagedListenerContainerFactory.class, ExtractionConsumer.class, ExtractionErrorHandler.class})
    public MessageListenerContainer fileExtractionListenerContainer(
            ManagedListenerContainerFactory factory,
            ExtractionConsumer consumer,
            ExtractionErrorHandler errorHandler) {
        MessageListenerContainer container = factory.create(
                ExtractionTopology.topology(),
                ExtractionMessage.class,
                consumer,
                errorHandler);
        container.start();
        return container;
    }
}
