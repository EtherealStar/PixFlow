package com.pixflow.harness.state.config;

import com.pixflow.harness.state.observability.MicrometerStateMetrics;
import com.pixflow.harness.state.observability.NoopStateMetrics;
import com.pixflow.harness.state.observability.StateMetrics;
import com.pixflow.harness.state.port.CheckpointReadPort;
import com.pixflow.harness.state.port.TaskRuntimeKeyPort;
import com.pixflow.harness.state.query.DefaultExecutionStateService;
import com.pixflow.harness.state.query.ExecutionStateService;
import com.pixflow.harness.state.recovery.DefaultRecoveryCoordinator;
import com.pixflow.harness.state.recovery.RecoveryCoordinator;
import com.pixflow.harness.state.runtime.CancellationReader;
import com.pixflow.harness.state.runtime.DefaultCancellationReader;
import com.pixflow.harness.state.runtime.DefaultProgressReader;
import com.pixflow.harness.state.runtime.DefaultRunStateRefStore;
import com.pixflow.harness.state.runtime.ProgressReader;
import com.pixflow.harness.state.runtime.RunStateRefStore;
import com.pixflow.infra.cache.counter.AtomicCounter;
import com.pixflow.infra.cache.store.CacheStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StateProperties.class)
public class StateAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock stateClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MeterRegistry.class)
    public StateMetrics micrometerStateMetrics(MeterRegistry meterRegistry) {
        return new MicrometerStateMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public StateMetrics noopStateMetrics() {
        return new NoopStateMetrics();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(CacheStore.class)
    public RunStateRefStore runStateRefStore(CacheStore cacheStore) {
        return new DefaultRunStateRefStore(cacheStore);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({CheckpointReadPort.class, TaskRuntimeKeyPort.class, AtomicCounter.class})
    public ProgressReader progressReader(
            CheckpointReadPort checkpointReadPort,
            TaskRuntimeKeyPort keyPort,
            AtomicCounter counter,
            StateProperties properties,
            StateMetrics metrics) {
        return new DefaultProgressReader(checkpointReadPort, keyPort, counter, properties, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({CacheStore.class, TaskRuntimeKeyPort.class})
    public CancellationReader cancellationReader(CacheStore cacheStore, TaskRuntimeKeyPort keyPort) {
        return new DefaultCancellationReader(cacheStore, keyPort);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(CheckpointReadPort.class)
    public RecoveryCoordinator recoveryCoordinator(CheckpointReadPort checkpointReadPort, StateMetrics metrics) {
        return new DefaultRecoveryCoordinator(checkpointReadPort, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({CheckpointReadPort.class, ProgressReader.class, CancellationReader.class})
    public ExecutionStateService executionStateService(
            CheckpointReadPort checkpointReadPort,
            ProgressReader progressReader,
            CancellationReader cancellationReader,
            StateMetrics metrics,
            Clock clock) {
        return new DefaultExecutionStateService(
                checkpointReadPort, progressReader, cancellationReader, metrics, clock);
    }
}
