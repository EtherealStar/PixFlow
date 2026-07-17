package com.pixflow.harness.permission.config;

import com.pixflow.harness.permission.DefaultPermissionPolicy;
import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.permission.proof.AdministratorEligibilityPort;
import com.pixflow.harness.permission.proof.AssetAuthorizationPort;
import com.pixflow.harness.permission.proof.ConversationAuthorizationPort;
import com.pixflow.harness.permission.proof.ProposalAuthorizationPort;
import com.pixflow.harness.permission.proof.TaskAuthorizationPort;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class PermissionAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public PermissionPolicy permissionPolicy(
            AdministratorEligibilityPort administratorEligibility,
            ConversationAuthorizationPort conversationAuthorization,
            AssetAuthorizationPort assetAuthorization,
            ProposalAuthorizationPort proposalAuthorization,
            TaskAuthorizationPort taskAuthorization) {
        return new DefaultPermissionPolicy(
                administratorEligibility,
                conversationAuthorization,
                assetAuthorization,
                proposalAuthorization,
                taskAuthorization);
    }
}
