package com.pixflow.infra.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.chat.ChatModelClient;
import com.pixflow.infra.ai.chat.ChatRequest;
import com.pixflow.infra.ai.embedding.EmbeddingClient;
import com.pixflow.infra.ai.error.AiErrorCode;
import com.pixflow.infra.ai.imagegen.ImageGenClient;
import com.pixflow.infra.ai.imagegen.ImageGenRequest;
import com.pixflow.infra.ai.model.ModelRole;
import com.pixflow.infra.ai.rerank.RerankClient;
import com.pixflow.infra.ai.vision.VisionModelClient;
import com.pixflow.infra.ai.vision.VisionRequest;
import com.pixflow.infra.ai.spi.GlobalConcurrencyLimiter;
import com.pixflow.infra.ai.spi.ModelQuotaLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AiAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiAutoConfiguration.class))
            .withPropertyValues(quotaProperties())
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(GlobalConcurrencyLimiter.class, () -> (role, provider, waitTime) -> () -> { })
            .withBean(ModelQuotaLimiter.class, () -> (role, provider, group, cost) ->
                    new ModelQuotaLimiter.QuotaDecision(true, 100, java.time.Duration.ZERO));

    @Test
    void createsAllModelClientsWithoutApiKey() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ChatModelClient.class);
            assertThat(context).hasSingleBean(VisionModelClient.class);
            assertThat(context).hasSingleBean(EmbeddingClient.class);
            assertThat(context).hasSingleBean(ImageGenClient.class);
            assertThat(context).hasSingleBean(RerankClient.class);
        });
    }

    @Test
    void bindsArbitraryOpenAiCompatibleProviderLabel() {
        contextRunner.withPropertyValues(
                        "pixflow.ai.default-provider=custom-anything",
                        "pixflow.ai.providers.custom-anything.api-key=test-key",
                        "pixflow.ai.providers.custom-anything.base-url=http://127.0.0.1:1/v1",
                        "pixflow.ai.roles.primary-chat.provider=custom-anything",
                        "pixflow.ai.roles.primary-chat.model=test-model",
                        "pixflow.ai.roles.primary-chat.capability=CHAT")
                .run(context -> {
                    AiProperties properties = context.getBean(AiProperties.class);

                    assertThat(properties.provider("CUSTOM-ANYTHING").apiKey()).isEqualTo("test-key");
                    assertThat(properties.provider("custom-anything").baseUrl()).endsWith("/v1");
                });
    }

    @Test
    void bindsQuotaRolesAndDefaultsRetryToThree() {
        contextRunner.run(context -> {
            AiProperties properties = context.getBean(AiProperties.class);

            assertThat(properties.retry().maxRetries()).isEqualTo(3);
            assertThat(properties.quota(ModelRole.PRIMARY_CHAT).quotaGroup()).isEqualTo("chat");
        });
    }

    @Test
    void legacyQuotasPathDoesNotSatisfyRequiredRoleConfiguration() {
        ApplicationContextRunner runner = baseRunner()
                .withBean(GlobalConcurrencyLimiter.class, () -> (role, provider, waitTime) -> () -> { })
                .withBean(ModelQuotaLimiter.class, () -> (role, provider, group, cost) ->
                        new ModelQuotaLimiter.QuotaDecision(true, 100, java.time.Duration.ZERO))
                .withPropertyValues(quotaPropertiesWithoutPrimary())
                .withPropertyValues(
                        "pixflow.ai.quotas.primary-chat.quota-group=legacy",
                        "pixflow.ai.quotas.primary-chat.capacity=10",
                        "pixflow.ai.quotas.primary-chat.refill-tokens=10",
                        "pixflow.ai.quotas.primary-chat.refill-period=1s",
                        "pixflow.ai.quotas.primary-chat.idle-ttl=1m",
                        "pixflow.ai.quotas.primary-chat.cost-per-attempt=1");

        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void partialRoleBindingCannotSkipRequiredQuotaValidation() {
        baseRunner()
                .withBean(GlobalConcurrencyLimiter.class, () -> (role, provider, waitTime) -> () -> { })
                .withBean(ModelQuotaLimiter.class, () -> (role, provider, group, cost) ->
                        new ModelQuotaLimiter.QuotaDecision(true, 100, java.time.Duration.ZERO))
                .withPropertyValues(
                        "pixflow.ai.roles.primary-chat.provider=dashscope",
                        "pixflow.ai.roles.primary-chat.model=qwen-max",
                        "pixflow.ai.roles.primary-chat.capability=CHAT")
                .withPropertyValues(quotaPropertiesWithoutPrimary())
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingEitherAdmissionAdapterFailsStartup() {
        baseRunner().withPropertyValues(quotaProperties())
                .withBean(ModelQuotaLimiter.class, () -> (role, provider, group, cost) ->
                        new ModelQuotaLimiter.QuotaDecision(true, 100, java.time.Duration.ZERO))
                .run(context -> assertThat(context).hasFailed());

        baseRunner().withPropertyValues(quotaProperties())
                .withBean(GlobalConcurrencyLimiter.class, () -> (role, provider, waitTime) -> () -> { })
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingApiKeyFailsAtCallTime() {
        contextRunner.run(context -> {
            ChatModelClient client = context.getBean(ChatModelClient.class);
            ChatRequest request = new ChatRequest(
                    null,
                    List.of(new ChatMessage(ChatMessage.Role.USER, List.of(new ChatMessage.TextPart("hello")))),
                    null,
                    null,
                    null);

            assertThatThrownBy(() -> client.call(request))
                    .isInstanceOf(PixFlowException.class)
                    .extracting(error -> ((PixFlowException) error).code())
                    .isEqualTo(AiErrorCode.MODEL_CONFIGURATION_ERROR);
        });
    }

    @Test
    void missingApiKeyFailsAtCallTimeForOtherClients() {
        contextRunner.run(context -> {
            VisionModelClient vision = context.getBean(VisionModelClient.class);
            EmbeddingClient embedding = context.getBean(EmbeddingClient.class);
            ImageGenClient imageGen = context.getBean(ImageGenClient.class);
            RerankClient rerank = context.getBean(RerankClient.class);

            assertThatThrownBy(() -> vision.call(new VisionRequest(ModelRole.VISION, List.of(userText("look")), null)))
                    .isInstanceOf(PixFlowException.class)
                    .extracting(error -> ((PixFlowException) error).code())
                    .isEqualTo(AiErrorCode.MODEL_CONFIGURATION_ERROR);
            assertThatThrownBy(() -> embedding.embed(List.of("hello")))
                    .isInstanceOf(PixFlowException.class)
                    .extracting(error -> ((PixFlowException) error).code())
                    .isEqualTo(AiErrorCode.MODEL_CONFIGURATION_ERROR);
            assertThatThrownBy(() -> imageGen.generate(new ImageGenRequest(new byte[] {1, 2}, "image/png", "redraw", null)))
                    .isInstanceOf(PixFlowException.class)
                    .extracting(error -> ((PixFlowException) error).code())
                    .isEqualTo(AiErrorCode.MODEL_CONFIGURATION_ERROR);
            assertThatThrownBy(() -> rerank.rerank("query", List.of("candidate")))
                    .isInstanceOf(PixFlowException.class)
                    .extracting(error -> ((PixFlowException) error).code())
                    .isEqualTo(AiErrorCode.MODEL_CONFIGURATION_ERROR);
        });
    }

    private static ChatMessage userText(String text) {
        return new ChatMessage(ChatMessage.Role.USER, List.of(new ChatMessage.TextPart(text)));
    }

    private static ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AiAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);
    }

    private static String[] quotaProperties() {
        java.util.List<String> properties = new java.util.ArrayList<>();
        addQuotaProperties(properties, "primary-chat", "chat");
        addQuotaProperties(properties, "vision", "vision");
        addQuotaProperties(properties, "imagegen", "imagegen");
        addQuotaProperties(properties, "embedding", "embedding");
        addQuotaProperties(properties, "rerank", "rerank");
        return properties.toArray(String[]::new);
    }

    private static String[] quotaPropertiesWithoutPrimary() {
        java.util.List<String> properties = new java.util.ArrayList<>();
        addQuotaProperties(properties, "vision", "vision");
        addQuotaProperties(properties, "imagegen", "imagegen");
        addQuotaProperties(properties, "embedding", "embedding");
        addQuotaProperties(properties, "rerank", "rerank");
        return properties.toArray(String[]::new);
    }

    private static void addQuotaProperties(java.util.List<String> properties, String role, String group) {
        String prefix = "pixflow.ai.quota.roles." + role;
        properties.add(prefix + ".quota-group=" + group);
        properties.add(prefix + ".capacity=10");
        properties.add(prefix + ".refill-tokens=10");
        properties.add(prefix + ".refill-period=1s");
        properties.add(prefix + ".idle-ttl=1m");
        properties.add(prefix + ".cost-per-attempt=1");
    }
}
