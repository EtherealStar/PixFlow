package com.pixflow.harness.tools.config;

import com.pixflow.harness.hooks.HookRegistry;
import com.pixflow.harness.tools.DefaultToolRegistry;
import com.pixflow.harness.tools.ToolDescriptor;
import com.pixflow.harness.tools.ToolExecutor;
import com.pixflow.harness.tools.RegistryToolExecutor;
import com.pixflow.harness.tools.ToolRegistry;
import com.pixflow.harness.tools.plan.PlanModeView;
import com.pixflow.harness.tools.result.NoopToolTraceSink;
import com.pixflow.harness.tools.result.ToolTraceSink;
import com.pixflow.infra.storage.toolresult.ToolResultStorage;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ToolsProperties.class)
public class ToolsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(
            List<ToolDescriptor> descriptors,
            com.pixflow.harness.permission.PermissionPolicy permissionPolicy) {
        return new DefaultToolRegistry(descriptors, permissionPolicy);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolTraceSink toolTraceSink() {
        return new NoopToolTraceSink();
    }

    @Bean
    @ConditionalOnMissingBean
    public PlanModeView planModeView() {
        return () -> false;
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolExecutor toolExecutor(
            ToolRegistry toolRegistry,
            com.pixflow.harness.permission.PermissionPolicy permissionPolicy,
            HookRegistry hookRegistry,
            ToolResultStorage resultStorage,
            ToolTraceSink traceSink,
            PlanModeView planModeView,
            ToolsProperties properties) {
        return new RegistryToolExecutor(toolRegistry, permissionPolicy, hookRegistry, resultStorage, traceSink, planModeView, properties);
    }
}
