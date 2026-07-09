package com.pixflow.harness.hooks.config;

import com.pixflow.harness.hooks.DefaultHookRegistry;
import com.pixflow.harness.hooks.HookCallback;
import com.pixflow.harness.hooks.HookRegistry;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;

@AutoConfiguration
@EnableConfigurationProperties(HookProperties.class)
public class HookAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HookRegistry hookRegistry(List<HookCallback> callbacks, HookProperties properties) {
        return new DefaultHookRegistry(callbacks, properties);
    }
}
