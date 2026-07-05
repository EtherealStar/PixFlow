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
import com.pixflow.infra.ai.rerank.RerankClient;
import com.pixflow.infra.ai.vision.VisionModelClient;
import com.pixflow.infra.ai.vision.VisionRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AiAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AiAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);

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

            assertThatThrownBy(() -> vision.call(new VisionRequest(List.of(userText("look")), null)))
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
}
