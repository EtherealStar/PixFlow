package com.pixflow.module.conversation.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.pixflow.harness.session.persistence.MessageReadMapper;
import com.pixflow.module.conversation.api.MessageController;
import com.pixflow.module.conversation.api.SseTurnSessionFactory;
import com.pixflow.module.conversation.app.TurnPreparationService;
import com.pixflow.module.conversation.persistence.ConversationMapper;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ConversationAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConversationAutoConfiguration.class))
            .withUserConfiguration(RequiredBeans.class)
            .withBean("conversationMapper", ConversationMapper.class, () -> mock(ConversationMapper.class));

    @Test
    void registersMessageControllerWithoutPackageReferenceResolver() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TurnPreparationService.class);
            assertThat(context).hasSingleBean(SseTurnSessionFactory.class);
            assertThat(context).hasSingleBean(MessageController.class);
            assertThat(context).doesNotHaveBean("attachmentCollector");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class RequiredBeans {
        @Bean
        MessageReadMapper messageReadMapper() {
            return mock(MessageReadMapper.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
