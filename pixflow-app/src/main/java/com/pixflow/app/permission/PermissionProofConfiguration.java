package com.pixflow.app.permission;

import com.pixflow.harness.permission.proof.AdministratorEligibilityPort;
import com.pixflow.harness.permission.proof.TaskAuthorizationPort;
import com.pixflow.infra.auth.identity.AdministratorEligibility;
import com.pixflow.module.task.api.authorization.TaskAuthorizationFactsQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PermissionProofConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public AdministratorEligibilityPort administratorEligibilityPermissionProof(
            AdministratorEligibility eligibility) {
        return new AuthAdministratorPermissionProof(eligibility);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskAuthorizationPort taskAuthorizationPermissionProof(TaskAuthorizationFactsQuery factsQuery) {
        return new TaskPermissionProof(factsQuery);
    }
}
