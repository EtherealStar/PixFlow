package com.pixflow.harness.context.config;

import com.pixflow.harness.context.budget.ConservativeTokenEstimator;
import com.pixflow.harness.context.budget.ContextBudgetService;
import com.pixflow.harness.context.budget.TokenEstimator;
import com.pixflow.harness.context.compaction.ContextCompactionService;
import com.pixflow.harness.context.compaction.SummarizationPort;
import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.store.MessageStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ContextAutoConfiguration.class));

    @Test
    void providesDefaultContextInfrastructureBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TokenEstimator.class);
            assertThat(context).hasSingleBean(ContextBudgetService.class);
            assertThat(context).hasSingleBean(ContextCompactionService.class);
            assertThat(context.getBean(TokenEstimator.class)).isInstanceOf(ConservativeTokenEstimator.class);
        });
    }

    @Test
    void backsOffWhenCustomTokenEstimatorExists() {
        TokenEstimator customEstimator = messages -> 42;

        contextRunner.withBean(TokenEstimator.class, () -> customEstimator)
                .run(context -> {
                    assertThat(context).hasSingleBean(TokenEstimator.class);
                    assertThat(context.getBean(TokenEstimator.class)).isSameAs(customEstimator);
                });
    }

    @Test
    void wiresOptionalSummarizationPortWithoutAgentDependency() {
        SummarizationPort summarizationPort = request -> new SummarizationPort.SummaryResult("fake summary");

        contextRunner.withBean(SummarizationPort.class, () -> summarizationPort)
                .run(context -> {
                    MessageStore store = new MessageStore();
                    store.appendUser("old context");
                    store.appendUser("latest context");

                    ContextCompactionService service = context.getBean(ContextCompactionService.class);

                    assertThat(service.manualCompact(store, "focus", null).summarized()).isTrue();
                    assertThat(store.currentMessages())
                            .extracting(Message::content)
                            .anySatisfy(content -> assertThat(content).contains("fake summary"));
                });
    }

    @Test
    void allowsMissingSummarizationPort() {
        contextRunner.run(context -> {
            MessageStore store = new MessageStore();
            store.appendUser("old context");
            store.appendUser("latest context");

            ContextCompactionService service = context.getBean(ContextCompactionService.class);

            assertThat(service.manualCompact(store, "focus", null).fallback()).isTrue();
        });
    }
}
