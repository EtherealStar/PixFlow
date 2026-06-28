package com.pixflow.harness.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.harness.hooks.config.HookAutoConfiguration;
import com.pixflow.harness.hooks.payload.HookPayload;
import com.pixflow.harness.hooks.payload.RuntimeScope;
import com.pixflow.harness.hooks.payload.UserPromptSubmitPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class HookAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HookAutoConfiguration.class))
            .withUserConfiguration(TestCallbacks.class);

    @Test
    void autoConfigurationCollectsCallbackBeansAndDispatchesInOrder() {
        contextRunner.run(context -> {
            HookRegistry registry = context.getBean(HookRegistry.class);
            TestCallbacks.calls.clear();

            HookResult result = registry.dispatch(HookEvent.USER_PROMPT_SUBMIT, new UserPromptSubmitPayload(
                    "conversation-1",
                    1,
                    "trace-1",
                    RuntimeScope.main(),
                    "hello",
                    Map.of(),
                    Map.of()));

            assertThat(TestCallbacks.calls).containsExactly("early", "late");
            assertThat(result.metadata()).containsEntry("ordered", List.of("early", "late"));
        });
    }

    @Test
    void autoConfigurationBacksOffWhenUserProvidesHookRegistry() {
        contextRunner.withUserConfiguration(CustomRegistryConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(HookRegistry.class);

            HookResult result = context.getBean(HookRegistry.class).dispatch(HookEvent.USER_PROMPT_SUBMIT, new UserPromptSubmitPayload(
                    "conversation-1",
                    1,
                    "trace-1",
                    RuntimeScope.main(),
                    "hello",
                    Map.of(),
                    Map.of()));

            assertThat(result.metadata()).containsEntry("custom", true);
        });
    }

    @Configuration
    static class TestCallbacks {
        static final List<String> calls = new ArrayList<>();

        @Bean
        HookCallback lateCallback() {
            return ordered(10, "late");
        }

        @Bean
        HookCallback earlyCallback() {
            return ordered(-10, "early");
        }

        private static HookCallback ordered(int order, String value) {
            return new HookCallback() {
                @Override
                public Set<HookEvent> supportedEvents() {
                    return Set.of(HookEvent.USER_PROMPT_SUBMIT);
                }

                @Override
                public int order() {
                    return order;
                }

                @Override
                public HookResult handle(HookEvent event, HookPayload payload) {
                    calls.add(value);
                    return HookResult.withMetadata(Map.of("ordered", value));
                }
            };
        }
    }

    @Configuration
    static class CustomRegistryConfiguration {
        @Bean
        HookRegistry customHookRegistry() {
            return (event, payload) -> HookResult.withMetadata(Map.of("custom", true));
        }
    }
}
