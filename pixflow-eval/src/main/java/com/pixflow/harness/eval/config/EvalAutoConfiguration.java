package com.pixflow.harness.eval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.eval.api.TraceQuery;
import com.pixflow.harness.eval.api.TraceRecorder;
import com.pixflow.harness.eval.api.TraceReplay;
import com.pixflow.harness.eval.error.EvalErrorRecorder;
import com.pixflow.harness.eval.recorder.DefaultTraceRecorder;
import com.pixflow.harness.eval.recorder.NoopTraceRecorder;
import com.pixflow.harness.eval.recorder.TraceIngestBuffer;
import com.pixflow.harness.eval.retention.TraceRetentionJob;
import com.pixflow.harness.eval.store.AgentTraceRepository;
import com.pixflow.harness.eval.store.DefaultTraceQuery;
import com.pixflow.harness.eval.store.DefaultTraceReplay;
import com.pixflow.harness.eval.store.InMemoryAgentTraceRepository;
import com.pixflow.harness.eval.support.ObjectStorageTraceExternalPayloadStorage;
import com.pixflow.harness.eval.support.TraceExternalPayloadStorage;
import com.pixflow.harness.eval.support.TracePayloadCodec;
import com.pixflow.infra.storage.ObjectStorage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;

@AutoConfiguration
@EnableConfigurationProperties(EvalProperties.class)
public class EvalAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MeterRegistry evalMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentTraceRepository agentTraceRepository() {
        return new InMemoryAgentTraceRepository();
    }

    @Bean
    @ConditionalOnBean(ObjectStorage.class)
    @ConditionalOnMissingBean
    public TraceExternalPayloadStorage objectStorageTraceExternalPayloadStorage(ObjectStorage objectStorage) {
        return new ObjectStorageTraceExternalPayloadStorage(objectStorage);
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceExternalPayloadStorage noopTraceExternalPayloadStorage() {
        return new TraceExternalPayloadStorage() {
            @Override
            public com.pixflow.harness.eval.model.TraceExternalPayloadRef put(String payload) {
                return new com.pixflow.harness.eval.model.TraceExternalPayloadRef("inline-unavailable", payload.length(), null, null, payload, true);
            }

            @Override
            public String get(com.pixflow.harness.eval.model.TraceExternalPayloadRef ref) {
                return ref.preview();
            }

            @Override
            public void delete(com.pixflow.harness.eval.model.TraceExternalPayloadRef ref) {
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public TracePayloadCodec tracePayloadCodec(ObjectMapper objectMapper, EvalProperties properties, TraceExternalPayloadStorage storage) {
        return new TracePayloadCodec(objectMapper, properties, storage);
    }

    @Bean
    @ConditionalOnProperty(prefix = "pixflow.eval", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public TraceIngestBuffer traceIngestBuffer(
            EvalProperties properties,
            TracePayloadCodec codec,
            AgentTraceRepository repository,
            MeterRegistry meterRegistry) {
        return new TraceIngestBuffer(properties, codec, repository, meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "pixflow.eval", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public TraceRecorder traceRecorder(TraceIngestBuffer buffer) {
        return new DefaultTraceRecorder(buffer);
    }

    @Bean
    @ConditionalOnProperty(prefix = "pixflow.eval", name = "enabled", havingValue = "false")
    @ConditionalOnMissingBean
    public TraceRecorder noopTraceRecorder() {
        return new NoopTraceRecorder();
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceQuery traceQuery(AgentTraceRepository repository, TracePayloadCodec codec) {
        return new DefaultTraceQuery(repository, codec);
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceReplay traceReplay(AgentTraceRepository repository, TracePayloadCodec codec) {
        return new DefaultTraceReplay(repository, codec);
    }

    @Bean
    @ConditionalOnMissingBean
    public EvalErrorRecorder evalErrorRecorder(MeterRegistry meterRegistry) {
        return new EvalErrorRecorder(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceRetentionJob traceRetentionJob(EvalProperties properties, AgentTraceRepository repository) {
        return new TraceRetentionJob(properties, repository);
    }
}
