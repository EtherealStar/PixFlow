package com.pixflow.harness.session.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.pixflow.harness.session.buffer.TranscriptBuffer;
import com.pixflow.harness.session.chain.ActiveChainResolver;
import com.pixflow.harness.session.externalize.SessionToolResultExternalizer;
import com.pixflow.harness.session.history.DefaultTranscriptHistoryReader;
import com.pixflow.harness.session.history.TranscriptHistoryReader;
import com.pixflow.harness.session.history.TranscriptDeletionService;
import com.pixflow.harness.session.mapping.MessageMapper;
import com.pixflow.harness.session.persistence.CompactionMapper;
import com.pixflow.harness.session.persistence.MessageReadMapper;
import com.pixflow.harness.session.persistence.MessageWriteMapper;
import com.pixflow.harness.session.persistence.TranscriptDeletionMapper;
import com.pixflow.harness.session.persistence.TranscriptService;
import com.pixflow.harness.session.seq.SequenceAllocator;
import com.pixflow.infra.storage.toolresult.ToolResultStorage;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(SessionProperties.class)
@MapperScan(value = "com.pixflow.harness.session.persistence", annotationClass = Mapper.class)
public class SessionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MessageMapper sessionMessageMapper(ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(
                () -> new ObjectMapper().registerModule(new JavaTimeModule()));
        return new MessageMapper(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public SequenceAllocator sequenceAllocator(MessageWriteMapper writeMapper) {
        return new SequenceAllocator(writeMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public TranscriptBuffer transcriptBuffer(SessionProperties properties) {
        return new TranscriptBuffer(
                properties.getBuffer().getFlushMaxMessages(),
                properties.getBuffer().getFlushMaxBytes().toBytes());
    }

    @Bean
    @ConditionalOnMissingBean
    public ActiveChainResolver activeChainResolver(MessageReadMapper readMapper, CompactionMapper compactionMapper) {
        return new ActiveChainResolver(readMapper, compactionMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public TranscriptHistoryReader transcriptHistoryReader(
            MessageReadMapper readMapper, MessageMapper messageMapper) {
        return new DefaultTranscriptHistoryReader(readMapper, messageMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public TranscriptDeletionService transcriptDeletionService(
            TranscriptDeletionMapper deletionMapper,
            TranscriptBuffer buffer) {
        return new TranscriptDeletionService(deletionMapper, buffer);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ToolResultStorage.class)
    public SessionToolResultExternalizer sessionToolResultExternalizer(
            ToolResultStorage storage,
            SessionProperties properties) {
        return new SessionToolResultExternalizer(
                storage,
                properties.getExternalize().getToolResultThreshold().toBytes(),
                properties.getExternalize().getPreviewChars());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SessionToolResultExternalizer.class)
    public TranscriptService transcriptService(
            MessageWriteMapper writeMapper,
            MessageReadMapper readMapper,
            CompactionMapper compactionMapper,
            MessageMapper messageMapper,
            SequenceAllocator sequenceAllocator,
            TranscriptBuffer buffer,
            ActiveChainResolver activeChainResolver,
            SessionToolResultExternalizer externalizer,
            SessionProperties properties,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new TranscriptService(
                writeMapper,
                readMapper,
                compactionMapper,
                messageMapper,
                sequenceAllocator,
                buffer,
                activeChainResolver,
                externalizer,
                properties,
                meterRegistryProvider.getIfAvailable());
    }
}
