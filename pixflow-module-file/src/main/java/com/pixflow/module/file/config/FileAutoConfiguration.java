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
import com.pixflow.contracts.asset.CanonicalAssetReferenceCodec;
import com.pixflow.module.file.copydoc.CsvCopyDocParser;
import com.pixflow.module.file.copydoc.ExcelCopyDocParser;
import com.pixflow.module.file.error.AssetIngestErrorMapper;
import com.pixflow.module.file.image.AssetImageMapper;
import com.pixflow.module.file.runtime.AssetImageQuery;
import com.pixflow.module.file.internal.image.DefaultAssetImageQuery;
import com.pixflow.module.file.internal.image.DefaultAssetContentReader;
import com.pixflow.module.file.api.AssetContentReader;
import com.pixflow.module.file.internal.publication.AssetImageLineageSourceMapper;
import com.pixflow.module.file.internal.publication.DefaultGeneratedImagePublisher;
import com.pixflow.module.file.internal.publication.GeneratedImagePublicationRecovery;
import com.pixflow.module.file.ingest.ExtractionConsumer;
import com.pixflow.module.file.ingest.ExtractionErrorHandler;
import com.pixflow.module.file.ingest.ExtractionPublisher;
import com.pixflow.module.file.ingest.ExtractionDestination;
import com.pixflow.module.file.ingest.ImageAdmission;
import com.pixflow.module.file.ingest.PublishGapRescan;
import com.pixflow.module.file.ingest.ZipExtractor;
import com.pixflow.module.file.ingest.RarExtractor;
import com.pixflow.module.file.ingest.SevenZExtractor;
import com.pixflow.module.file.ingest.ArchiveExtractor;
import com.pixflow.module.file.ingest.ArchiveEntryProcessor;
import com.pixflow.module.file.ingest.ArchiveSafetyPolicy;
import com.pixflow.module.file.ingest.ArchiveExtractionDispatcher;
import com.pixflow.module.file.naming.DefaultSkuExtractor;
import com.pixflow.module.file.naming.FileNameParser;
import com.pixflow.module.file.naming.SkuExtractor;
import com.pixflow.module.file.pkg.AssetPackageMapper;
import com.pixflow.module.file.pkg.AssetPackageService;
import com.pixflow.module.file.permission.AssetPermissionProof;
import com.pixflow.module.file.api.AssetReferenceResolver;
import com.pixflow.module.file.internal.reference.DefaultAssetReferenceService;
import com.pixflow.module.file.internal.reference.DefaultAssetReferenceCatalog;
import com.pixflow.module.file.api.AssetReferenceCatalog;
import com.pixflow.module.file.api.AssetDeletionService;
import com.pixflow.module.file.internal.deletion.AssetCleanupIntentMapper;
import com.pixflow.module.file.internal.deletion.AssetReferenceTombstoneMapper;
import com.pixflow.module.file.internal.deletion.DefaultAssetDeletionService;
import com.pixflow.module.file.internal.deletion.AssetDeletionRecovery;
import com.pixflow.module.file.internal.deletion.DefaultAssetReferenceHistory;
import com.pixflow.module.file.api.AssetReferenceHistory;
import com.pixflow.module.file.upload.UploadSessionService;
import com.pixflow.module.file.upload.UploadSessionStore;
import com.pixflow.module.file.upload.RedisUploadSessionStore;
import com.pixflow.module.file.upload.UploadOrphanCleanup;
import com.pixflow.module.file.web.FileController;
import java.time.Clock;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
                AssetImageLineageSourceMapper.class,
                AssetIngestErrorMapper.class,
                AssetCopyMapper.class,
                AssetCleanupIntentMapper.class,
                AssetReferenceTombstoneMapper.class
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
    public CanonicalAssetReferenceCodec canonicalAssetReferenceCodec() {
        return new CanonicalAssetReferenceCodec();
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultAssetReferenceService assetReferenceService(
            CanonicalAssetReferenceCodec codec,
            AssetPackageService packageService,
            AssetImageMapper imageMapper,
            AssetReferenceTombstoneMapper tombstoneMapper) {
        return new DefaultAssetReferenceService(codec, packageService, imageMapper, tombstoneMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AssetReferenceCatalog assetReferenceCatalog(
            CanonicalAssetReferenceCodec codec,
            AssetPackageMapper packageMapper,
            AssetImageMapper imageMapper) {
        return new DefaultAssetReferenceCatalog(codec, packageMapper, imageMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AssetDeletionService assetDeletionService(
            AssetPackageMapper packageMapper,
            AssetImageMapper imageMapper,
            AssetCopyMapper copyMapper,
            AssetIngestErrorMapper errorMapper,
            AssetReferenceTombstoneMapper tombstoneMapper,
            AssetCleanupIntentMapper intentMapper,
            ObjectStorage objectStorage,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        return new DefaultAssetDeletionService(packageMapper, imageMapper, copyMapper, errorMapper,
                tombstoneMapper, intentMapper, objectStorage,
                new TransactionTemplate(transactionManager), clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public AssetDeletionRecovery assetDeletionRecovery(AssetDeletionService deletionService) {
        return new AssetDeletionRecovery(deletionService);
    }

    @Bean
    @ConditionalOnMissingBean
    public AssetReferenceHistory assetReferenceHistory(
            CanonicalAssetReferenceCodec codec, AssetReferenceTombstoneMapper tombstoneMapper) {
        return new DefaultAssetReferenceHistory(codec, tombstoneMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AssetPermissionProof assetPermissionProof(
            CanonicalAssetReferenceCodec codec, AssetReferenceResolver referenceResolver) {
        return new AssetPermissionProof(codec, referenceResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public AssetImageQuery assetImageQuery(AssetImageMapper imageMapper) {
        return new DefaultAssetImageQuery(imageMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AssetContentReader assetContentReader(
            CanonicalAssetReferenceCodec codec,
            AssetImageMapper imageMapper,
            ObjectStorage objectStorage) {
        return new DefaultAssetContentReader(codec, imageMapper, objectStorage);
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultGeneratedImagePublisher generatedImagePublisher(
            AssetImageMapper imageMapper,
            AssetImageLineageSourceMapper lineageMapper,
            ObjectStorage objectStorage,
            CanonicalAssetReferenceCodec codec,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        return new DefaultGeneratedImagePublisher(
                imageMapper, lineageMapper, objectStorage, codec,
                new TransactionTemplate(transactionManager), clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public GeneratedImagePublicationRecovery generatedImagePublicationRecovery(
            AssetImageMapper imageMapper,
            DefaultGeneratedImagePublisher publisher,
            Clock clock) {
        return new GeneratedImagePublicationRecovery(imageMapper, publisher, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public AssetPackageService assetPackageService(
            AssetPackageMapper packageMapper,
            Clock clock) {
        return new AssetPackageService(packageMapper, clock);
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
            ObjectStorage objectStorage,
            CanonicalAssetReferenceCodec referenceCodec,
            AssetDeletionService deletionService) {
        return new FileService(
                packageService,
                packageMapper,
                imageMapper,
                errorMapper,
                objectStorage,
                referenceCodec,
                deletionService);
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
    public FileController fileController(FileService fileService,
                                         UploadSessionService uploadSessionService,
                                         AssetReferenceCatalog referenceCatalog) {
        return new FileController(fileService, uploadSessionService, referenceCatalog);
    }

    @Bean
    @ConditionalOnMissingBean
    public ArchiveSafetyPolicy archiveSafetyPolicy(FileProperties properties) {
        return new ArchiveSafetyPolicy(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public UploadOrphanCleanup uploadOrphanCleanup(
            UploadSessionStore store, ObjectStorage objectStorage, Clock clock) {
        return new UploadOrphanCleanup(store, objectStorage, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public ArchiveEntryProcessor archiveEntryProcessor(
            ObjectStorage objectStorage,
            AssetPackageService packageService,
            AssetImageMapper imageMapper,
            AssetIngestErrorMapper errorMapper,
            FileNameParser fileNameParser,
            ImageAdmission imageAdmission,
            ArchiveSafetyPolicy safetyPolicy,
            ProgressNotifier progressNotifier,
            Clock clock,
            AssetCopyMapper copyMapper,
            CsvCopyDocParser csvParser,
            ExcelCopyDocParser excelParser) {
        return new ArchiveEntryProcessor(objectStorage, packageService, imageMapper, errorMapper,
                fileNameParser, imageAdmission, safetyPolicy, progressNotifier, clock,
                copyMapper, csvParser, excelParser);
    }

    @Bean
    @ConditionalOnMissingBean
    public ZipExtractor zipExtractor(ObjectStorage objectStorage,
                                     AssetPackageService packageService,
                                     ArchiveEntryProcessor processor) {
        return new ZipExtractor(objectStorage, packageService, processor);
    }

    @Bean
    @ConditionalOnMissingBean
    public RarExtractor rarExtractor(ObjectStorage objectStorage,
                                     AssetPackageService packageService,
                                     ArchiveEntryProcessor processor) {
        return new RarExtractor(objectStorage, packageService, processor);
    }

    @Bean
    @ConditionalOnMissingBean
    public SevenZExtractor sevenZExtractor(ObjectStorage objectStorage,
                                           AssetPackageService packageService,
                                           ArchiveEntryProcessor processor) {
        return new SevenZExtractor(objectStorage, packageService, processor);
    }

    @Bean
    @ConditionalOnMissingBean
    public ArchiveExtractionDispatcher archiveExtractionDispatcher(
            AssetPackageService packageService, List<ArchiveExtractor> extractors) {
        return new ArchiveExtractionDispatcher(packageService, extractors);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExtractionConsumer extractionConsumer(ArchiveExtractionDispatcher dispatcher) {
        return new ExtractionConsumer(dispatcher);
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
