package com.pixflow.harness.loop;

import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.common.concurrent.CancellationToken;
import com.pixflow.harness.context.model.MessageReference;
import com.pixflow.harness.context.store.MessageStore;
import com.pixflow.infra.ai.chat.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentLoopMessageReferenceTest {
    @Test
    void rendersReferencesForModelWithoutChangingPersistedContent() {
        MessageStore store = new MessageStore();
        FakeChatModelClient model = new FakeChatModelClient().enqueueText("done");
        LoopTestSupport.Builder builder = LoopTestSupport.builder();
        builder.state(new RuntimeState());
        builder.store = store;
        builder.modelClient(model);
        AgentLoop loop = builder.build();
        List<MessageReference> references = List.of(
                new MessageReference("package:1", "summer.zip"),
                new MessageReference("package:1/image:2", "summer.zip / front.png"));

        loop.stream(
                "process",
                references,
                new RecordingAgentEventSink(),
                "system",
                List.of(),
                CancellationToken.NONE);

        assertThat(store.currentMessages().get(0).content()).isEqualTo("process");
        assertThat(store.currentMessages().get(0).metadata().references())
                .containsExactlyElementsOf(references);
        ChatMessage user = model.seenRequests().get(0).messages().stream()
                .filter(message -> message.role() == ChatMessage.Role.USER)
                .findFirst()
                .orElseThrow();
        assertThat(user.parts()).singleElement().isInstanceOfSatisfying(
                ChatMessage.TextPart.class,
                part -> assertThat(part.text())
                        .contains("process")
                        .contains("summer.zip [package:1]")
                        .contains("summer.zip / front.png [package:1/image:2]"));
    }
}
