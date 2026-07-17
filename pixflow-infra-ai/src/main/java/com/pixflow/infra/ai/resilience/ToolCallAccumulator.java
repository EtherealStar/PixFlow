package com.pixflow.infra.ai.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.RecoveryHint;
import com.pixflow.infra.ai.chat.ToolCall;
import com.pixflow.infra.ai.error.AiErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 流式 tool-call 分片累积器。
 */
public final class ToolCallAccumulator {
    private final ObjectMapper objectMapper;

    private final Map<Integer, MutableToolCall> calls = new LinkedHashMap<>();

    public ToolCallAccumulator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public void append(int index, String id, String name, String argumentsPart) {
        MutableToolCall call = calls.computeIfAbsent(index, MutableToolCall::new);
        call.merge(id, name, argumentsPart);
    }

    public List<ToolCall> complete() {
        List<ToolCall> result = new ArrayList<>();
        calls.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.add(entry.getValue().toToolCall(objectMapper)));
        return result;
    }

    private static final class MutableToolCall {
        private final int index;

        private String id;

        private String name;

        private final StringBuilder arguments = new StringBuilder();

        private MutableToolCall(int index) {
            this.index = index;
        }

        private void merge(String newId, String newName, String argumentsPart) {
            if (newId != null && !newId.isBlank()) {
                id = newId;
            }
            if (newName != null && !newName.isBlank()) {
                name = newName;
            }
            if (argumentsPart != null) {
                arguments.append(argumentsPart);
            }
        }

        private ToolCall toToolCall(ObjectMapper mapper) {
            String json = arguments.toString();
            try {
                mapper.readTree(json);
            } catch (Exception ex) {
                throw new PixFlowException(
                        AiErrorCode.INVALID_TOOL_ARGUMENTS,
                        "Invalid tool arguments",
                        ex,
                        Map.of("index", index, "toolName", name == null ? "" : name),
                        RecoveryHint.TERMINATE,
                        null,
                        null);
            }
            return new ToolCall(id, name == null ? "" : name, json);
        }
    }
}
