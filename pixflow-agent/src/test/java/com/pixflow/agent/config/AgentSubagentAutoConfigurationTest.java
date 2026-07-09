package com.pixflow.agent.config;

import com.pixflow.agent.subagent.SubagentRequest;
import com.pixflow.agent.subagent.SubagentRunner;
import com.pixflow.agent.subagent.SubagentResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSubagentAutoConfigurationTest {

    private static final String LEGACY_SUBAGENT_POOL_BEAN = "subagent" + "Pool";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentSubagentAutoConfiguration.class))
            .withUserConfiguration(SubagentRunnerConfiguration.class)
            .withPropertyValues(
                    "pixflow.agent.subagent.pool.core-size=2",
                    "pixflow.agent.subagent.pool.max-size=4",
                    "pixflow.agent.subagent.pool.queue-capacity=8",
                    "pixflow.agent.subagent.pool.keep-alive-seconds=30");

    @Test
    void creates_named_subagent_executor_without_legacy_subagent_pool() {
        contextRunner.run(context -> {
            assertThat(context).hasBean(AgentSubagentAutoConfiguration.SUBAGENT_EXECUTOR_BEAN);
            assertThat(context).hasBean(AgentSubagentAutoConfiguration.SESSION_MEMORY_EXECUTOR_BEAN);
            assertThat(context).doesNotHaveBean(LEGACY_SUBAGENT_POOL_BEAN);

            Map<String, ExecutorService> executors = context.getBeansOfType(ExecutorService.class);
            assertThat(executors).containsOnlyKeys(
                    AgentSubagentAutoConfiguration.SUBAGENT_EXECUTOR_BEAN,
                    AgentSubagentAutoConfiguration.SESSION_MEMORY_EXECUTOR_BEAN);

            ThreadPoolExecutor executor = context.getBean(
                    AgentSubagentAutoConfiguration.SUBAGENT_EXECUTOR_BEAN,
                    ThreadPoolExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaximumPoolSize()).isEqualTo(4);
            assertThat(executor.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(30);
        });
    }

    @Test
    void subagent_runner_injects_the_named_executor() {
        contextRunner.run(context -> {
            SubagentRunner runner = context.getBean(SubagentRunner.class);
            SubagentResult result = runner.runAsync(
                    SubagentRequest.explore("conv-1", "tc-1", "inspect competitor listings")
            ).get(5, TimeUnit.SECONDS);

            assertThat(result.isError()).isTrue();
            assertThat(result.errorMessage()).isEqualTo("subagent_runtime_unavailable");
        });
    }

    @Import(SubagentRunner.class)
    static class SubagentRunnerConfiguration {
    }
}
