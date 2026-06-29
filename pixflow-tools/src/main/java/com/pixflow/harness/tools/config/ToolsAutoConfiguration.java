package com.pixflow.harness.tools.config;

import com.pixflow.harness.hooks.HookRegistry;
import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.tools.DefaultToolRegistry;
import com.pixflow.harness.tools.ToolDescriptor;
import com.pixflow.harness.tools.ToolExecutor;
import com.pixflow.harness.tools.RegistryToolExecutor;
import com.pixflow.harness.tools.ToolRegistry;
import com.pixflow.harness.tools.plan.PlanModeView;
import com.pixflow.harness.tools.result.DefaultToolResultStorage;
import com.pixflow.harness.tools.result.NoopToolTraceSink;
import com.pixflow.harness.tools.result.ToolResultStorage;
import com.pixflow.harness.tools.result.ToolTraceSink;
import com.pixflow.infra.storage.ObjectStorage;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ToolsProperties.class)
public class ToolsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(List<ToolDescriptor> descriptors, PermissionPolicy permissionPolicy) {
        return new DefaultToolRegistry(descriptors, permissionPolicy);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolResultStorage toolResultStorage(ObjectStorage objectStorage) {
        return new DefaultToolResultStorage(objectStorage);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolTraceSink toolTraceSink() {
        return new NoopToolTraceSink();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolExecutor toolExecutor(
            ToolRegistry toolRegistry,
            PermissionPolicy permissionPolicy,
            HookRegistry hookRegistry,
            ToolResultStorage resultStorage,
            ToolTraceSink traceSink,
            PlanModeView planModeView,
            ToolsProperties properties) {
        return new RegistryToolExecutor(toolRegistry, permissionPolicy, hookRegistry, resultStorage, traceSink, planModeView, properties);
    }
}
