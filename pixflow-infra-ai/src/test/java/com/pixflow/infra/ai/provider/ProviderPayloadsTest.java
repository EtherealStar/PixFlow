package com.pixflow.infra.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.ai.chat.ChatMessage;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProviderPayloadsTest {
    @Test
    void convertsBytesImageContentToDataUri() {
        ChatMessage.BytesImageContent content = new ChatMessage.BytesImageContent(
                "png".getBytes(StandardCharsets.UTF_8),
                "image/png");

        assertThat(ProviderPayloads.imageUrl(content))
                .isEqualTo("data:image/png;base64,cG5n");
    }
}
