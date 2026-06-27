package com.pixflow.infra.ai.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.infra.ai.error.AiErrorCode;
import org.junit.jupiter.api.Test;

class ToolCallAccumulatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void accumulatesAndSortsByIndex() {
        ToolCallAccumulator accumulator = new ToolCallAccumulator(objectMapper);
        accumulator.append(1, "b", "tool-b", "{\"a\":");
        accumulator.append(1, null, null, "2}");
        accumulator.append(0, "a", "tool-a", "{\"x\":1}");

        assertThat(accumulator.complete()).hasSize(2);
        assertThat(accumulator.complete().get(0).name()).isEqualTo("tool-a");
        assertThat(accumulator.complete().get(1).name()).isEqualTo("tool-b");
    }

    @Test
    void rejectsInvalidJson() {
        ToolCallAccumulator accumulator = new ToolCallAccumulator(objectMapper);
        accumulator.append(0, "a", "tool-a", "{");

        assertThatThrownBy(accumulator::complete)
                .isInstanceOf(PixFlowException.class)
                .extracting("code")
                .isEqualTo(AiErrorCode.INVALID_TOOL_ARGUMENTS);
    }
}
