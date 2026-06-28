package com.pixflow.harness.state.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.harness.state.model.CompletedUnits;
import com.pixflow.harness.state.model.TaskRunStatus;
import com.pixflow.harness.state.port.CheckpointReadPort.PersistedCounts;
import com.pixflow.harness.state.query.ExecutionStateService;
import com.pixflow.harness.state.recovery.RecoveryCoordinator;
import com.pixflow.harness.state.runtime.CancellationReader;
import com.pixflow.harness.state.runtime.ProgressReader;
import com.pixflow.harness.state.runtime.RunStateRefStore;
import com.pixflow.harness.state.testsupport.FakeAtomicCounter;
import com.pixflow.harness.state.testsupport.FakeCacheStore;
import com.pixflow.harness.state.testsupport.FakeCheckpointReadPort;
import com.pixflow.harness.state.testsupport.FakeTaskRuntimeKeyPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class StateAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StateAutoConfiguration.class));

    @Test
    void runStateRefStoreCanBeCreatedWithOnlyCacheStore() {
        contextRunner.withUserConfiguration(CacheOnlyConfig.class).run(context -> {
            assertThat(context).hasSingleBean(RunStateRefStore.class);
            assertThat(context).doesNotHaveBean(ExecutionStateService.class);
        });
    }

    @Test
    void fullStateServicesRequirePortsAndCachePrimitives() {
        contextRunner.withUserConfiguration(FullConfig.class).run(context -> {
            assertThat(context).hasSingleBean(ProgressReader.class);
            assertThat(context).hasSingleBean(CancellationReader.class);
            assertThat(context).hasSingleBean(RecoveryCoordinator.class);
            assertThat(context).hasSingleBean(ExecutionStateService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CacheOnlyConfig {
        @Bean
        FakeCacheStore cacheStore() {
            return new FakeCacheStore();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FullConfig {
        @Bean
        FakeCacheStore cacheStore() {
            return new FakeCacheStore();
        }

        @Bean
        FakeAtomicCounter atomicCounter() {
            return new FakeAtomicCounter();
        }

        @Bean
        FakeTaskRuntimeKeyPort taskRuntimeKeyPort() {
            return new FakeTaskRuntimeKeyPort();
        }

        @Bean
        FakeCheckpointReadPort checkpointReadPort() {
            FakeCheckpointReadPort port = new FakeCheckpointReadPort();
            port.putTask(
                    "task-1",
                    CompletedUnits.empty("task-1"),
                    new PersistedCounts(1, 0, 0),
                    TaskRunStatus.RUNNING);
            return port;
        }
    }
}
