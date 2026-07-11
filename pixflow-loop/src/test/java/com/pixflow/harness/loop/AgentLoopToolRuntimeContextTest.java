package com.pixflow.harness.loop;

import com.pixflow.common.concurrent.CancellationToken;
import static org.assertj.core.api.Assertions.assertThat;

import com.pixflow.infra.ai.chat.ToolCall;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentLoopToolRuntimeContextTest {

    @Test
    void toolExecutionContextRuntimeContextWritesBackToCurrentState() {
        RuntimeState state = new RuntimeState();
        FakeToolExecutor toolExecutor = new FakeToolExecutor();
        FakeChatModelClient modelClient = new FakeChatModelClient()
                .enqueueToolCalls(List.of(new ToolCall("tc1", "plan", "{}")), "planning")
                .enqueueText("done");
        AgentLoop loop = LoopTestSupport.builder()
                .state(state)
                .modelClient(modelClient)
                .toolExecutor(toolExecutor)
                .build();

        loop.stream("make a plan", List.of(), new RecordingAgentEventSink(), "system", List.of(), CancellationToken.NONE);

        assertThat(toolExecutor.contextHistory()).hasSize(1);
        toolExecutor.contextHistory().get(0).runtimeContext().putMetadata("planMode", true);
        assertThat(loop.state().metadataOrDefault("planMode", false)).isEqualTo(true);
    }
}
