package com.pixflow.harness.context.budget;

import com.pixflow.harness.context.model.Message;
import com.pixflow.harness.context.model.MessageMetadata;
import com.pixflow.harness.context.model.ToolResultReference;
import com.pixflow.harness.context.snapshot.PreparedContext;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBudgetServiceTest {
    @Test
    void externalizesLargeToolResultAndMicrocompactsOldResults() {
        ToolResultExternalizer externalizer = (toolCallId, content, previewChars) ->
                new ToolResultReference("ref1", "TOOL_RESULTS", "ref1.txt", content.substring(0, previewChars), content.length(), false);
        ContextBudgetService service = new ContextBudgetService(
                new ContextBudgetConfig(20, 5, 20, 1),
                new ConservativeTokenEstimator(),
                externalizer);

        PreparedContext prepared = service.prepare(List.of(
                Message.assistantToolCall("call1", "t1"),
                Message.toolResult("t1", "small old"),
                Message.assistantToolCall("call2", "t2"),
                Message.toolResult("t2", "small recent"),
                Message.assistantToolCall("call3", "t3"),
                Message.toolResult("t3", "this is a very large tool result")));

        assertThat(prepared.transcriptRefs()).hasSize(1);
        assertThat(prepared.messages()).anySatisfy(message ->
                assertThat(message.metadata().flag(MessageMetadata.TOOL_RESULT_EXTERNALIZED)).isTrue());
        assertThat(prepared.messages()).anySatisfy(message ->
                assertThat(message.metadata().flag(MessageMetadata.MICROCOMPACTED)).isTrue());
        assertThat(prepared.usageHints()).containsKey("tokenAfter");
    }
}
