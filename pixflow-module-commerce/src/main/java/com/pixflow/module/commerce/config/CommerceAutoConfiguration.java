package com.pixflow.module.commerce.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.infra.mq.MessagePublisher;
import com.pixflow.infra.mq.consumer.ManagedListenerContainerFactory;
import com.pixflow.infra.mq.consumer.ManagedMessageContainer;
import com.pixflow.infra.mq.destination.DestinationRegistrar;
import com.pixflow.module.commerce.CommerceService;
import com.pixflow.module.commerce.DefaultCommerceService;
import com.pixflow.module.commerce.importer.CommerceFileParser;
import com.pixflow.module.commerce.importer.CommerceImportService;
import com.pixflow.module.commerce.importer.CsvCommerceParser;
import com.pixflow.module.commerce.importer.ExcelCommerceParser;
import com.pixflow.module.commerce.importer.RowValidator;
import com.pixflow.module.commerce.importjob.CommerceApiImportConsumer;
import com.pixflow.module.commerce.importjob.CommerceApiImportPublisher;
import com.pixflow.module.commerce.importjob.CommerceImportErrorHandler;
import com.pixflow.module.commerce.importjob.CommerceImportJobService;
import com.pixflow.module.commerce.importjob.CommerceImportDestination;
import com.pixflow.module.commerce.query.BenchmarkCalculator;
import com.pixflow.module.commerce.query.CommerceQueryService;
import com.pixflow.module.commerce.source.CommerceDataSource;
import com.pixflow.module.commerce.source.ExternalPlatformSource;
import com.pixflow.module.commerce.source.FakePlatformApiClient;
import com.pixflow.module.commerce.source.FreshnessPolicy;
import com.pixflow.module.commerce.source.PlatformApiClient;
import com.pixflow.module.commerce.store.CommerceDataMapper;
import com.pixflow.module.commerce.store.CommerceImportJobMapper;
import com.pixflow.module.commerce.web.CommerceController;
import java.time.Clock;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(CommerceProperties.class)
@MapperScan(
        basePackages = "com.pixflow.module.commerce.store",
        annotationClass = Mapper.class)
public class CommerceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public CsvCommerceParser csvCommerceParser() {
        return new CsvCommerceParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public ExcelCommerceParser excelCommerceParser() {
        return new ExcelCommerceParser();
    }

    @Bean
    @ConditionalOnMissingBean
    public RowValidator commerceRowValidator(Clock clock) {
        return new RowValidator(clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public PlatformApiClient platformApiClient() {
        return new FakePlatformApiClient();
    }

    @Bean
    @ConditionalOnMissingBean
    public CommerceDataSource externalPlatformSource(
            PlatformApiClient platformApiClient,
            CommerceProperties properties,
            Clock clock) {
        return new ExternalPlatformSource(platformApiClient, properties, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public FreshnessPolicy freshnessPolicy(CommerceProperties properties, Clock clock) {
        return new FreshnessPolicy(properties, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public BenchmarkCalculator benchmarkCalculator() {
        return new BenchmarkCalculator();
    }

    @Bean
    @ConditionalOnMissingBean
    public CommerceImportService commerceImportService(
            CommerceDataMapper mapper,
            CsvCommerceParser csvParser,
            ExcelCommerceParser excelParser,
            RowValidator rowValidator,
            CommerceProperties properties) {
        List<CommerceFileParser> parsers = List.of(csvParser, excelParser);
        return new CommerceImportService(mapper, parsers, csvParser, excelParser, rowValidator, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommerceApiImportPublisher commerceApiImportPublisher(MessagePublisher publisher) {
        return new CommerceApiImportPublisher(publisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommerceImportJobService commerceImportJobService(
            CommerceImportJobMapper mapper,
            CommerceApiImportPublisher publisher,
            CommerceDataSource externalSource,
            CommerceImportService importService,
            ObjectMapper objectMapper,
            CommerceProperties properties,
            Clock clock) {
        return new CommerceImportJobService(mapper, publisher, externalSource, importService, objectMapper, properties, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommerceQueryService commerceQueryService(
            CommerceDataMapper mapper,
            CommerceDataSource externalSource,
            CommerceImportService importService,
            FreshnessPolicy freshnessPolicy,
            BenchmarkCalculator benchmarkCalculator,
            CommerceProperties properties,
            Clock clock) {
        return new CommerceQueryService(mapper, externalSource, importService, freshnessPolicy, benchmarkCalculator, properties, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommerceService commerceService(
            CommerceImportService importService,
            CommerceImportJobService jobService,
            CommerceQueryService queryService) {
        return new DefaultCommerceService(importService, jobService, queryService);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommerceController commerceController(CommerceService commerceService) {
        return new CommerceController(commerceService);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(CommerceImportJobService.class)
    public CommerceApiImportConsumer commerceApiImportConsumer(CommerceImportJobService jobService) {
        return new CommerceApiImportConsumer(jobService);
    }

    @Bean
    @ConditionalOnMissingBean
    public CommerceImportErrorHandler commerceImportErrorHandler() {
        return new CommerceImportErrorHandler();
    }

    @Bean
    @ConditionalOnBean(DestinationRegistrar.class)
    public Object commerceImportDestinationRegistration(DestinationRegistrar registrar) {
        registrar.register(CommerceImportDestination.destination(0));
        registrar.register(CommerceImportDestination.binding());
        return new Object();
    }

    @Bean
    @ConditionalOnBean({ManagedListenerContainerFactory.class, CommerceApiImportConsumer.class, CommerceImportErrorHandler.class})
    public ManagedMessageContainer commerceImportListenerContainer(
            ManagedListenerContainerFactory factory,
            CommerceApiImportConsumer consumer,
            CommerceImportErrorHandler errorHandler) {
        ManagedMessageContainer container = factory.create(CommerceImportDestination.binding(), consumer, errorHandler);
        container.start();
        return container;
    }
}
