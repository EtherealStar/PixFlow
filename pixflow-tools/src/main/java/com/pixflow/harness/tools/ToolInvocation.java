package com.pixflow.harness.tools;

import com.pixflow.harness.hooks.payload.RuntimeScope;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolInvocation(
        String toolCallId,
        String toolName,
        Map<String, Object> arguments,
        String conversationId,
        Integer turnNo,
        String traceId,
        RuntimeScope runtimeScope,
        ToolRuntimeContext runtimeContext,
        ProposalPublicationAuthorizer proposalPublicationAuthorizer,
        Map<String, Object> metadata) {

    public ToolInvocation {
        arguments = immutableCopy(arguments);
        metadata = immutableCopy(metadata);
        runtimeScope = runtimeScope == null ? RuntimeScope.main() : runtimeScope;
        runtimeContext = runtimeContext == null ? ToolRuntimeContext.unavailable() : runtimeContext;
        proposalPublicationAuthorizer = proposalPublicationAuthorizer == null
                ? ProposalPublicationAuthorizer.unavailable() : proposalPublicationAuthorizer;
    }

    public ToolInvocation(
            String toolCallId,
            String toolName,
            Map<String, Object> arguments,
            String conversationId,
            Integer turnNo,
            String traceId,
            RuntimeScope runtimeScope,
            Map<String, Object> metadata) {
        this(toolCallId, toolName, arguments, conversationId, turnNo, traceId, runtimeScope,
                ToolRuntimeContext.unavailable(), ProposalPublicationAuthorizer.unavailable(), metadata);
    }

    public ToolInvocation(
            String toolCallId,
            String toolName,
            Map<String, Object> arguments,
            String conversationId,
            Integer turnNo,
            String traceId,
            RuntimeScope runtimeScope,
            ToolRuntimeContext runtimeContext,
            Map<String, Object> metadata) {
        this(toolCallId, toolName, arguments, conversationId, turnNo, traceId, runtimeScope,
                runtimeContext, ProposalPublicationAuthorizer.unavailable(), metadata);
    }

    private static Map<String, Object> immutableCopy(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
