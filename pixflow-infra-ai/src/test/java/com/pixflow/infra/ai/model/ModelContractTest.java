package com.pixflow.infra.ai.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pixflow.infra.ai.chat.ChatMessage;
import com.pixflow.infra.ai.chat.ChatRequest;
import com.pixflow.infra.ai.chat.ToolChoice;
import com.pixflow.infra.ai.chat.ToolSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelContractTest {

    @Test
    void defensiveCopiesBinaryContent() {
        byte[] bytes = new byte[] {1, 2, 3};
        ChatMessage.BytesImageContent content = new ChatMessage.BytesImageContent(bytes, "image/png");
        bytes[0] = 9;

        assertThat(content.bytes()[0]).isEqualTo((byte) 1);
    }

    @Test
    void requestCopiesCollections() {
        List<ChatMessage> messages = new ArrayList<>(List.of(new ChatMessage(ChatMessage.Role.USER, List.of(new ChatMessage.TextPart("hi")))));
        ChatRequest request = new ChatRequest(ModelRole.PRIMARY_CHAT, messages, List.of(new ToolSchema("tool", "desc", "{}")), ToolChoice.AUTO, new ChatOptions(0.2d, 128, Duration.ofSeconds(2)), null);
        messages.add(new ChatMessage(ChatMessage.Role.ASSISTANT, List.of(new ChatMessage.TextPart("later"))));

        assertThat(request.messages()).hasSize(1);
        assertThat(request.toolChoice()).isEqualTo(ToolChoice.AUTO);
    }

    @Test
    void validatesRequiredValues() {
        assertThatThrownBy(() -> new TokenUsage(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResolvedModel(null, "p", "m", ModelCapability.CHAT, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
