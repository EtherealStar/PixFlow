package com.pixflow.harness.permission.config;

import com.pixflow.contracts.confirmation.ConfirmationTokenStore;
import com.pixflow.harness.permission.DefaultPermissionPolicy;
import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.permission.token.ConfirmationTokenService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(PermissionProperties.class)
public class PermissionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ConfirmationTokenService confirmationTokenService(
            ConfirmationTokenStore tokenStore,
            Clock clock) {
        return new ConfirmationTokenService(tokenStore, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public PermissionPolicy permissionPolicy(
            ConfirmationTokenService confirmationTokenService,
            PermissionProperties properties) {
        return new DefaultPermissionPolicy(confirmationTokenService, properties.getBulkThreshold());
    }
}
