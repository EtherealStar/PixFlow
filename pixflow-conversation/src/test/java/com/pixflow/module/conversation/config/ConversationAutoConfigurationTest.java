package com.pixflow.module.conversation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.session.history.TranscriptDeletionService;
import com.pixflow.harness.session.history.TranscriptHistoryReader;
import com.pixflow.module.conversation.app.CancellationService;
import com.pixflow.module.conversation.app.ConfirmationService;
import com.pixflow.module.conversation.app.ConversationService;
import com.pixflow.module.conversation.app.MessageReferenceValidator;
import com.pixflow.module.conversation.app.TurnPreparationService;
import com.pixflow.module.conversation.lock.ConversationLock;
import com.pixflow.module.conversation.permission.ConversationPermissionProofs;
import com.pixflow.module.conversation.persistence.ConversationMapper;
import com.pixflow.module.conversation.proposal.ProposalService;
import com.pixflow.module.file.api.AssetReferenceResolver;
import com.pixflow.module.imagegen.confirm.ImagegenPayloadHasher;
import com.pixflow.module.task.api.TaskCommandService;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ConversationAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConversationAutoConfiguration.class))
            .withUserConfiguration(RequiredBeans.class)
            .withBean("conversationMapper", ConversationMapper.class, () -> mock(ConversationMapper.class));

    @Test
    void registersApplicationServicesWithoutModuleWebController() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TurnPreparationService.class);
            assertThat(context).hasSingleBean(ConversationPermissionProofs.class);
            assertThat(context).hasSingleBean(ConfirmationService.class);
            assertThat(context).hasSingleBean(CancellationService.class);
            assertThat(context).doesNotHaveBean("messageController");
            assertThat(context).doesNotHaveBean("attachmentCollector");
        });
    }

    @Test
    void missingAssetReferenceResolverFailsFastAtMessageReferenceValidator() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConversationAutoConfiguration.class))
                .withUserConfiguration(RequiredBeansWithoutAssetResolver.class)
                .withBean("conversationMapper", ConversationMapper.class,
                        () -> mock(ConversationMapper.class))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class)
                            .hasMessageContaining(AssetReferenceResolver.class.getName());
                });
    }

    @Test
    void coreApplicationBeansAreNotSilentlyConditional() throws NoSuchMethodException {
        assertThat(ConversationAutoConfiguration.class
                .getDeclaredMethod("messageReferenceValidator",
                        PermissionPolicy.class, AssetReferenceResolver.class)
                .getAnnotation(ConditionalOnBean.class))
                .isNull();
        assertThat(ConversationAutoConfiguration.class
                .getDeclaredMethod("turnPreparationService",
                        ConversationService.class,
                        ConversationLock.class,
                        MessageReferenceValidator.class,
                        com.pixflow.module.conversation.app.AgentTurnRunnerRegistry.class)
                .getAnnotation(ConditionalOnBean.class))
                .isNull();
        assertThat(ConversationAutoConfiguration.class
                .getDeclaredMethod("conversationPermissionProofs",
                        ConversationService.class, ProposalService.class)
                .getAnnotation(ConditionalOnBean.class))
                .isNull();
        assertThat(ConversationAutoConfiguration.class
                .getDeclaredMethod("conversationConfirmationService",
                        ConversationService.class,
                        ProposalService.class,
                        PermissionPolicy.class,
                        TaskCommandService.class,
                        ObjectMapper.class,
                        ImagegenPayloadHasher.class)
                .getAnnotation(ConditionalOnBean.class))
                .isNull();
        assertThat(ConversationAutoConfiguration.class
                .getDeclaredMethod("conversationCancellationService",
                        ConversationService.class, TaskCommandService.class)
                .getAnnotation(ConditionalOnBean.class))
                .isNull();
    }

    @Configuration(proxyBeanMethods = false)
    static class RequiredBeans extends RequiredBeansWithoutAssetResolver {
        @Bean
        AssetReferenceResolver assetReferenceResolver() {
            return mock(AssetReferenceResolver.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RequiredBeansWithoutAssetResolver {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        TranscriptDeletionService transcriptDeletionService() {
            return mock(TranscriptDeletionService.class);
        }

        @Bean
        TranscriptHistoryReader transcriptHistoryReader() {
            return mock(TranscriptHistoryReader.class);
        }

        @Bean
        PermissionPolicy permissionPolicy() {
            return mock(PermissionPolicy.class);
        }

        @Bean
        TaskCommandService taskCommandService() {
            return mock(TaskCommandService.class);
        }

        @Bean
        ImagegenPayloadHasher imagegenPayloadHasher() {
            return mock(ImagegenPayloadHasher.class);
        }
    }
}
