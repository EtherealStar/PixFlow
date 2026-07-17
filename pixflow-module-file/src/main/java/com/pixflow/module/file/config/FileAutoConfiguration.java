package com.pixflow.module.file.config;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.pixflow.common.progress.ProgressNotifier;
import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.cache.key.CacheNamespace;
import com.pixflow.infra.cache.lock.LockTemplate;
import com.pixflow.infra.cache.state.ExpiringHashStore;
import com.pixflow.infra.cache.state.ExpiringStateStore;
import com.pixflow.infra.cache.config.CacheAutoConfiguration;
import com.pixflow.infra.mq.consumer.ManagedListenerContainerFactory;
import com.pixflow.infra.mq.consumer.ManagedMessageContainer;
import com.pixflow.infra.mq.destination.DestinationRegistrar;
import com.pixflow.infra.mq.config.MqAutoConfiguration;
import com.pixflow.infra.storage.ObjectStorage;
import com.pixflow.infra.storage.config.StorageAutoConfiguration;
import com.pixflow.module.file.FileService;
import com.pixflow.module.file.copydoc.AssetCopyMapper;
import com.pixflow.module.file.copydoc.CsvCopyDocParser;
import com.pixflow.module.file.copydoc.ExcelCopyDocParser;
import com.pixflow.module.file.error.AssetIngestErrorMapper;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.image.DefaultSourceImageReader;
import com.pixflow.module.file.ingest.ExtractionConsumer;
import com.pixflow.module.file.ingest.ExtractionErrorHandler;
import com.pixflow.module.file.ingest.ExtractionPublisher;
import com.pixflow.module.file.ingest.ExtractionDestination;
import com.pixflow.module.file.ingest.ImageAdmission;
import com.pixflow.module.file.ingest.PublishGapRescan;
import com.pixflow.module.file.ingest.ZipExtractor;
import com.pixflow.module.file.naming.DefaultSkuExtractor;
import com.pixflow.module.file.naming.FileNameParser;
import com.pixflow.module.file.naming.SkuExtractor;
import com.pixflow.module.file.pkg.AssetPackageMapper;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.pkg.ConservativePackageReferenceChecker;
import com.pixflow.module.file.pkg.DefaultPackageReferenceResolver;
import com.pixflow.module.file.pkg.PackageReferenceChecker;
import com.pixflow.module.file.pkg.PackageReferenceResolver;
import com.pixflow.module.file.permission.AssetPermissionProof;
import com.pixflow.module.file.upload.UploadSessionService;
import com.pixflow.module.file.upload.UploadSessionStore;
import com.pixflow.module.file.upload.RedisUploadSessionStore;
import com.pixflow.module.file.web.FileController;
import com.pixflow.module.imagegen.port.SourceImageReader;
import java.time.Clock;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.mybatis.spring.annotation.MapperScan;

@AutoConfiguration(after = {
        StorageAutoConfiguration.class,
        CacheAutoConfiguration.class,
        MqAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
})
@EnableConfigurationProperties(FileProperties.class)
@EnableScheduling
@MapperScan(
        basePackageClasses = {
                AssetPackageMapper.class,
                AssetImageMapper.class,
                AssetIngestErrorMapper.class,
                AssetCopyMapper.class
        },
        annotationClass = Mapper.class)
public class FileAutoConfiguration {

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
        return new ConservativePackageReferenceChecker();
    }

    @Bean
    @ConditionalOnMissingBean
    public PackageReferenceResolver packageReferenceResolver(
            AssetPackageService packageService,
            AssetImageMapper imageMapper) {
        return new DefaultPackageReferenceResolver(packageService, imageMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AssetPermissionProof assetPermissionProof(
            AssetPackageService packageService, AssetImageMapper imageMapper) {
        return new AssetPermissionProof(packageService, imageMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public SourceImageReader sourceImageReader(AssetImageMapper imageMapper) {
        return new DefaultSourceImageReader(imageMapper);
    }

    @Bean
    @ConditionalOnMissingBean
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
    public FileService fileService(
            AssetPackageService packageService,
            AssetPackageMapper packageMapper,
            AssetImageMapper imageMapper,
            AssetIngestErrorMapper errorMapper,
            AssetCopyMapper copyMapper,
            CsvCopyDocParser csvCopyDocParser,
            ExcelCopyDocParser excelCopyDocParser,
            ObjectStorage objectStorage,
            ExtractionPublisher extractionPublisher,
            Clock clock) {
        return new FileService(
                packageService,
                packageMapper,
                imageMapper,
                errorMapper,
                copyMapper,
                csvCopyDocParser,
                excelCopyDocParser,
                objectStorage,
                extractionPublisher,
                clock);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ExpiringStateStore.class, ExpiringHashStore.class})
    public UploadSessionStore uploadSessionStore(
            ExpiringStateStore stateStore,
            ExpiringHashStore hashStore,
            CacheNamespace cacheNamespace,
            FileProperties properties,
            Clock clock) {
        return new RedisUploadSessionStore(stateStore, hashStore, cacheNamespace,
                properties.getUpload().getSessionTtl(), clock);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
            UploadSessionStore.class,
            LockTemplate.class,
            ObjectStorage.class,
            ExtractionPublisher.class
    })
    public UploadSessionService uploadSessionService(
            UploadSessionStore uploadSessionStore,
            LockTemplate lockTemplate,
            CacheNamespace cacheNamespace,
            ObjectStorage objectStorage,
            AssetPackageMapper packageMapper,
            AssetPackageService packageService,
            ExtractionPublisher extractionPublisher,
            FileProperties properties,
            Clock clock) {
        return new UploadSessionService(uploadSessionStore, lockTemplate, cacheNamespace, objectStorage,
                packageMapper, packageService, extractionPublisher, properties, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(UploadSessionService.class)
    public FileController fileController(FileService fileService, UploadSessionService uploadSessionService) {
        return new FileController(fileService, uploadSessionService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({
            ObjectStorage.class,
            AssetPackageService.class,
            AssetImageMapper.class,
            AssetIngestErrorMapper.class
    })
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
    @ConditionalOnBean(DestinationRegistrar.class)
    public Object fileExtractionDestinationRegistration(DestinationRegistrar registrar) {
        registrar.register(ExtractionDestination.destination(0));
        registrar.register(ExtractionDestination.binding());
        return new Object();
    }

    @Bean
    @ConditionalOnBean({ManagedListenerContainerFactory.class, ExtractionConsumer.class, ExtractionErrorHandler.class})
    public ManagedMessageContainer fileExtractionListenerContainer(
            ManagedListenerContainerFactory factory,
            ExtractionConsumer consumer,
            ExtractionErrorHandler errorHandler) {
        ManagedMessageContainer container = factory.create(ExtractionDestination.binding(), consumer, errorHandler);
        container.start();
        return container;
    }
}
