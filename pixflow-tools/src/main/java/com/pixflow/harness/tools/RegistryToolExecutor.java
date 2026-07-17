package com.pixflow.harness.tools;

import com.pixflow.common.concurrent.CancellationToken;
import com.pixflow.common.concurrent.OperationCancelledException;
import com.pixflow.common.error.PixFlowException;
import com.pixflow.common.error.render.ToolErrorRenderer;
import com.pixflow.harness.hooks.HookEvent;
import com.pixflow.harness.hooks.HookRegistry;
import com.pixflow.harness.hooks.HookResult;
import com.pixflow.harness.hooks.payload.ToolUsePayload;
import com.pixflow.harness.permission.PermissionAction;
import com.pixflow.harness.permission.PermissionDecision;
import com.pixflow.harness.permission.PermissionSubject;
import com.pixflow.harness.tools.error.ToolErrorFactory;
import com.pixflow.harness.tools.plan.PlanModeView;
import com.pixflow.harness.tools.result.ToolTraceSink;
import com.pixflow.infra.storage.toolresult.StoredToolResultReference;
import com.pixflow.infra.storage.toolresult.ToolResultStorage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class RegistryToolExecutor implements ToolExecutor {
    private final ToolRegistry toolRegistry;

    private final com.pixflow.harness.permission.PermissionPolicy permissionPolicy;

    private final HookRegistry hookRegistry;

    private final ToolResultStorage resultStorage;

    private final ToolTraceSink traceSink;

    private final PlanModeView planModeView;

    private final int maxConcurrency;

    public RegistryToolExecutor(
            ToolRegistry toolRegistry,
            com.pixflow.harness.permission.PermissionPolicy permissionPolicy,
            HookRegistry hookRegistry,
            ToolResultStorage resultStorage,
            ToolTraceSink traceSink,
            PlanModeView planModeView,
            com.pixflow.harness.tools.config.ToolsProperties properties) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.permissionPolicy = Objects.requireNonNull(permissionPolicy, "permissionPolicy");
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "hookRegistry");
        this.resultStorage = Objects.requireNonNull(resultStorage, "resultStorage");
        this.traceSink = Objects.requireNonNull(traceSink, "traceSink");
        this.planModeView = planModeView;
        this.maxConcurrency = Math.max(1, properties.getMaxConcurrency());
    }

    @Override
    public List<ToolExecutionResult> execute(List<ToolCall> calls, ToolExecutionContext context) {
        List<ToolExecutionResult> results = new ArrayList<>(calls.size());
        ExecutorService executor = context.executor();
        CancellationToken cancellation = context.cancellation();
        for (int index = 0; index < calls.size(); ) {
            cancellation.throwIfCancellationRequested();
            ToolCall call = calls.get(index);
            ToolDescriptor descriptor = toolRegistry.get(call.toolName()).orElse(null);
            if (descriptor == null) {
                results.add(error(
                        call,
                        "unknown tool",
                        ToolErrorFactory.validation(
                                "未知工具: " + call.toolName(),
                                Map.of("toolName", call.toolName()))));
                index++;
                continue;
            }
            boolean batchCandidate = descriptor.classifier().classify(descriptor, call.arguments()).concurrencySafe();
            if (!batchCandidate) {
                results.add(executeSingle(call, descriptor, context));
                index++;
                continue;
            }
            int batchStart = index;
            int batchEnd = index;
            while (batchEnd < calls.size()) {
                ToolCall next = calls.get(batchEnd);
                ToolDescriptor nextDescriptor = toolRegistry.get(next.toolName()).orElse(null);
                if (nextDescriptor == null) {
                    break;
                }
                ToolCallClassification nextClassification = nextDescriptor
                        .classifier()
                        .classify(nextDescriptor, next.arguments());
                if (!nextClassification.concurrencySafe()) {
                    break;
                }
                batchEnd++;
            }
            if (batchEnd - batchStart == 1) {
                results.add(executeSingle(call, descriptor, context));
                index++;
                continue;
            }
            List<Future<ToolExecutionResult>> futures = new ArrayList<>();
            for (int i = batchStart; i < batchEnd; i++) {
                cancellation.throwIfCancellationRequested();
                ToolCall batchCall = calls.get(i);
                ToolDescriptor batchDescriptor = toolRegistry.get(batchCall.toolName()).orElseThrow();
                futures.add(executor.submit(() -> executeSingle(batchCall, batchDescriptor, context)));
            }
            // 使用 Future.cancel(true) 才能尽力中断底层任务；CompletableFuture.cancel 不保证中断执行线程。
            cancellation.cancellationSignal().thenRun(() ->
                    futures.forEach(future -> future.cancel(true)));
            for (Future<ToolExecutionResult> future : futures) {
                results.add(join(future, cancellation));
            }
            index = batchEnd;
        }
        return results;
    }

    private ToolExecutionResult executeSingle(
            ToolCall call,
            ToolDescriptor descriptor,
            ToolExecutionContext context) {
        long startedAt = Instant.now().toEpochMilli();
        Map<String, Object> args = new LinkedHashMap<>(call.arguments());
        boolean rewritten = false;
        try {
            context.cancellation().throwIfCancellationRequested();
            validateArgs(descriptor, args);
            ToolCallClassification classification = descriptor.classifier().classify(descriptor, args);
            PermissionDecision decision = evaluatePermission(
                    call, descriptor, classification, context.permissionContext());
            if (decision.action() == PermissionAction.DENY || decision.action() == PermissionAction.CONFIRM_REQUIRED) {
                ToolExecutionResult result = error(
                        call,
                        "permission denied",
                        ToolErrorFactory.permission(
                                decision.reason(),
                                Map.of("decision", decision.action().name())));
                trace(call, startedAt, result, rewritten, true, decision.source().name());
                return result;
            }
            HookResult pre = hookRegistry.dispatch(
                    HookEvent.PRE_TOOL_USE,
                    new ToolUsePayload(
                            call.conversationId(),
                            call.turnNo(),
                            call.traceId(),
                            call.runtimeScope(),
                            HookEvent.PRE_TOOL_USE,
                            call.toolName(),
                            call.toolCallId(),
                            args,
                            decision,
                            call.metadata()));
            if (pre.blocked()) {
                ToolExecutionResult result = error(
                        call,
                        pre.blockingReason(),
                        ToolErrorFactory.validation(pre.blockingReason(), pre.metadata()));
                trace(call, startedAt, result, rewritten, false, "HOOK");
                return result;
            }
            if (pre.inputRewritten()) {
                rewritten = true;
                args.clear();
                args.putAll(pre.updatedInput());
                validateArgs(descriptor, args);
                classification = descriptor.classifier().classify(descriptor, args);
                decision = evaluatePermission(call, descriptor, classification, context.permissionContext());
                if (decision.action() != PermissionAction.ALLOW) {
                    ToolExecutionResult result = error(
                            call,
                            "permission denied",
                            ToolErrorFactory.permission(
                                    decision.reason(),
                                    Map.of("decision", decision.action().name())));
                    trace(call, startedAt, result, rewritten, true, decision.source().name());
                    return result;
                }
            }
            ToolExecutionResult result = invokeHandler(call, descriptor, args, classification, context);
            context.cancellation().throwIfCancellationRequested();
            trace(call, startedAt, result, rewritten, false, null);
            return result;
        } catch (OperationCancelledException cancelled) {
            throw cancelled;
        } catch (PixFlowException ex) {
            ToolExecutionResult result = error(call, ex.getMessage(), ex);
            trace(call, startedAt, result, rewritten, true, ex.category().name());
            return result;
        } catch (RuntimeException ex) {
            PixFlowException error = ToolErrorFactory.internal("工具执行失败", Map.of("toolName", call.toolName()));
            ToolExecutionResult result = error(call, error.getMessage(), error);
            trace(call, startedAt, result, rewritten, true, error.category().name());
            return result;
        }
    }

    private ToolExecutionResult invokeHandler(
            ToolCall call,
            ToolDescriptor descriptor,
            Map<String, Object> args,
            ToolCallClassification classification,
            ToolExecutionContext context) {
        context.cancellation().throwIfCancellationRequested();
        ToolInvocation invocation = new ToolInvocation(
                call.toolCallId(),
                call.toolName(),
                args,
                call.conversationId(),
                call.turnNo(),
                call.traceId(),
                call.runtimeScope(),
                context.runtimeContext(),
                call.metadata());
        ToolHandlerOutput output = descriptor.handler().handle(invocation);
        context.cancellation().throwIfCancellationRequested();
        String content = output.content();
        Map<String, Object> metadata = new LinkedHashMap<>(output.metadata());
        metadata.putAll(classification.subjectMetadata());
        metadata.put("readOnly", classification.readOnly());
        metadata.put("concurrencySafe", classification.concurrencySafe());
        return applyBudget(call, descriptor, content, metadata, classification.resultPolicy());
    }

    private static ToolExecutionResult join(
            Future<ToolExecutionResult> future,
            CancellationToken cancellation) {
        cancellation.throwIfCancellationRequested();
        try {
            ToolExecutionResult result = future.get();
            cancellation.throwIfCancellationRequested();
            return result;
        } catch (CancellationException cancelled) {
            cancellation.throwIfCancellationRequested();
            throw cancelled;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancellation.throwIfCancellationRequested();
            throw new RuntimeException(interrupted);
        } catch (ExecutionException execution) {
            if (execution.getCause() instanceof OperationCancelledException cancelled) {
                throw cancelled;
            }
            if (execution.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(execution.getCause());
        }
    }

    private ToolExecutionResult applyBudget(
            ToolCall call,
            ToolDescriptor descriptor,
            String content,
            Map<String, Object> metadata,
            ToolResultPolicy policy) {
        int length = content.length();
        if (length <= policy.maxResultSizeChars()) {
            return ToolExecutionResult.success(call.toolCallId(), call.toolName(), content, metadata);
        }
        String preview = content.substring(0, Math.min(policy.previewChars(), length));
        if (!policy.persistWhenExceeded()) {
            metadata.put("result_truncated", true);
            metadata.put("original_size_chars", length);
            return ToolExecutionResult.success(call.toolCallId(), call.toolName(), preview, metadata);
        }
        StoredToolResultReference stored = resultStorage.write(call.toolCallId(), content, policy.previewChars());
        metadata.put("result_truncated", true);
        metadata.put("original_size_chars", length);
        metadata.put("max_result_size_chars", policy.maxResultSizeChars());
        metadata.put("stored_ref", storedRef(stored));
        metadata.put("stored_reference", stored);
        return ToolExecutionResult.success(
                call.toolCallId(),
                call.toolName(),
                stored.preview().isEmpty() ? preview : stored.preview(),
                metadata);
    }

    private static String storedRef(StoredToolResultReference reference) {
        return "tool-results://" + reference.bucket().toLowerCase() + "/" + reference.key();
    }

    private void validateArgs(ToolDescriptor descriptor, Map<String, Object> args) {
        if (descriptor.inputSchema().isEmpty()) {
            descriptor.validator().validate(descriptor, args);
            return;
        }
        Object required = descriptor.inputSchema().get("required");
        if (required instanceof List<?> list) {
            for (Object key : list) {
                if (!args.containsKey(String.valueOf(key))) {
                    throw ToolErrorFactory.validation(
                            "缺少必填参数: " + key,
                            Map.of("toolName", descriptor.name(), "missing", key));
                }
            }
        }
        descriptor.validator().validate(descriptor, args);
    }

    private PermissionDecision evaluatePermission(
            ToolCall call,
            ToolDescriptor descriptor,
            ToolCallClassification classification,
            com.pixflow.harness.permission.PermissionContext context) {
        PermissionSubject subject = new PermissionSubject(
                descriptor.name(),
                classification.readOnly(),
                null,
                call.conversationId(),
                call.conversationId(),
                Integer.toHexString(Objects.hash(call.toolCallId(), call.toolName(), call.arguments())),
                1,
                classification.subjectMetadata());
        return permissionPolicy.evaluate(subject, context);
    }

    private ToolExecutionResult error(ToolCall call, String content, PixFlowException error) {
        Map<String, Object> payload = ToolErrorRenderer.render(error);
        payload.put("safeMessage", content);
        return ToolExecutionResult.error(call.toolCallId(), call.toolName(), content, payload);
    }

    private void trace(
            ToolCall call,
            long startedAt,
            ToolExecutionResult result,
            boolean rewritten,
            boolean resultExternalized,
            String errorCategory) {
        traceSink.record(new ToolTraceSink.ToolTraceEvent(
                call.toolName(),
                call.toolCallId(),
                startedAt,
                Instant.now().toEpochMilli(),
                result.error(),
                errorCategory,
                rewritten,
                resultExternalized,
                result.metadata()));
    }
}
