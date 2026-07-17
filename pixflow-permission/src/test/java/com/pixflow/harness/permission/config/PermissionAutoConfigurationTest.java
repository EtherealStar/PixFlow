package com.pixflow.harness.permission.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.permission.proof.AdministratorEligibilityPort;
import com.pixflow.harness.permission.proof.AssetAuthorizationPort;
import com.pixflow.harness.permission.proof.ConversationAuthorizationPort;
import com.pixflow.harness.permission.proof.ProposalAuthorizationPort;
import com.pixflow.harness.permission.proof.TaskAuthorizationPort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PermissionAutoConfigurationTest {
    @Test
    void registersExactlyOnePolicyWhenAllCurrentFactProofsExist() {
        runner(null).run(context -> assertThat(context).hasSingleBean(PermissionPolicy.class));
    }

    @Test
    void refusesToStartWhenAnyRequiredProofIsMissing() {
        List.of(
                AdministratorEligibilityPort.class,
                ConversationAuthorizationPort.class,
                AssetAuthorizationPort.class,
                ProposalAuthorizationPort.class,
                TaskAuthorizationPort.class)
                .forEach(missing -> runner(missing).run(context ->
                        // 授权证明不是可选增强；缺任一事实源都必须阻止应用启动。
                        assertThat(context.getStartupFailure()).isNotNull()));
    }

    private static ApplicationContextRunner runner(Class<?> missing) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PermissionAutoConfiguration.class));
        if (missing != AdministratorEligibilityPort.class) {
            runner = runner.withBean(
                    AdministratorEligibilityPort.class,
                    () -> mock(AdministratorEligibilityPort.class));
        }
        if (missing != ConversationAuthorizationPort.class) {
            runner = runner.withBean(
                    ConversationAuthorizationPort.class,
                    () -> mock(ConversationAuthorizationPort.class));
        }
        if (missing != AssetAuthorizationPort.class) {
            runner = runner.withBean(
                    AssetAuthorizationPort.class,
                    () -> mock(AssetAuthorizationPort.class));
        }
        if (missing != ProposalAuthorizationPort.class) {
            runner = runner.withBean(
                    ProposalAuthorizationPort.class,
                    () -> mock(ProposalAuthorizationPort.class));
        }
        if (missing != TaskAuthorizationPort.class) {
            runner = runner.withBean(
                    TaskAuthorizationPort.class,
                    () -> mock(TaskAuthorizationPort.class));
        }
        return runner;
    }
}
