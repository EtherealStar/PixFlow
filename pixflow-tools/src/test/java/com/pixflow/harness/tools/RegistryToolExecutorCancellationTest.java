package com.pixflow.harness.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pixflow.common.concurrent.CancellationReason;
import com.pixflow.common.concurrent.CancellationSource;
import com.pixflow.common.concurrent.CancellationToken;
import com.pixflow.common.concurrent.OperationCancelledException;
import com.pixflow.harness.hooks.HookRegistry;
import com.pixflow.harness.hooks.HookResult;
import com.pixflow.harness.permission.PermissionContext;
import com.pixflow.harness.permission.PermissionDecision;
import com.pixflow.harness.permission.PermissionErrorCode;
import com.pixflow.harness.permission.PermissionPlanMode;
import com.pixflow.harness.permission.PermissionPolicy;
import com.pixflow.harness.permission.PermissionPrincipal;
import com.pixflow.harness.permission.PermissionRuntimeScope;
import com.pixflow.harness.permission.PermissionSubject;
import com.pixflow.harness.permission.PermissionSource;
import com.pixflow.harness.tools.config.ToolsProperties;
import com.pixflow.harness.tools.result.ToolTraceSink;
import com.pixflow.infra.storage.toolresult.ToolResultStorage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RegistryToolExecutorCancellationTest {
    @Test
    void proposalPublicationIsReauthorizedAfterHandlerValidation() {
        AtomicInteger publications = new AtomicInteger();
        ToolDescriptor descriptor = descriptor("submit_image_plan", invocation -> {
            invocation.proposalPublicationAuthorizer().authorize(
                    "DAG", List.of("package:1/image:2"), "sha256:abc");
            publications.incrementAndGet();
            return ToolHandlerOutput.of("unexpected");
        });
        PermissionPolicy permission = new PermissionPolicy() {
            @Override
            public PermissionDecision evaluate(
                    PermissionContext context, PermissionSubject subject) {
                return subject instanceof PermissionSubject.ProposalPublication
                        ? PermissionDecision.deny(
                                "proposal_publication",
                                PermissionSource.ASSET,
                                PermissionErrorCode.PERMISSION_ASSET_DENIED)
                        : PermissionDecision.allow("submit_image_plan");
            }

            @Override
            public boolean isToolVisible(
                    String toolName, boolean readOnly, PermissionContext context) {
                return true;
            }
        };
        HookRegistry hooks = mock(HookRegistry.class);
        when(hooks.dispatch(any(), any())).thenReturn(HookResult.noop());
        RegistryToolExecutor executor = new RegistryToolExecutor(
                registry(Map.of(descriptor.name(), descriptor)), permission, hooks,
                mock(ToolResultStorage.class), () -> false,
                new ToolsProperties());
        PermissionContext permissionContext = new PermissionContext(
                new PermissionPrincipal("42", "admin"), PermissionRuntimeScope.MAIN,
                PermissionPlanMode.OFF, "conv", "turn");
        try (ExecutorService toolPool = Executors.newSingleThreadExecutor()) {
            ToolExecutionContext context = new ToolExecutionContext(
                    permission, permissionContext, hooks, mock(ToolResultStorage.class),
                    event -> { }, () -> false, ToolRuntimeContext.unavailable(), toolPool,
                    Set.of(), CancellationToken.NONE);

            ToolExecutionResult result = executor.execute(
                    List.of(call("call-1", descriptor.name())), context).getFirst();

            assertThat(result.error()).isTrue();
            assertThat(publications).hasValue(0);
        }
    }

    @Test
    void directInvocationIsDeniedBeforeHiddenToolHandlerRuns() {
        AtomicInteger handlerCalls = new AtomicInteger();
        ToolDescriptor descriptor = descriptor("submit_image_plan", invocation -> {
            handlerCalls.incrementAndGet();
            return ToolHandlerOutput.of("unexpected");
        });
        PermissionPolicy permission = new PermissionPolicy() {
            @Override
            public PermissionDecision evaluate(
                    PermissionContext context,
                    com.pixflow.harness.permission.PermissionSubject subject) {
                return PermissionDecision.deny(
                        "submit_image_plan",
                        PermissionSource.PLAN_MODE,
                        PermissionErrorCode.PERMISSION_PLAN_MODE_DENIED);
            }

            @Override
            public boolean isToolVisible(
                    String toolName, boolean readOnly, PermissionContext context) {
                return false;
            }
        };
        HookRegistry hooks = mock(HookRegistry.class);
        RegistryToolExecutor executor = new RegistryToolExecutor(
                registry(Map.of(descriptor.name(), descriptor)),
                permission,
                hooks,
                mock(ToolResultStorage.class),
                () -> true,
                new ToolsProperties());
        PermissionContext permissionContext = new PermissionContext(
                new PermissionPrincipal("42", "admin"),
                PermissionRuntimeScope.MAIN,
                PermissionPlanMode.ACTIVE,
                "conv",
                "turn");
        try (ExecutorService toolPool = Executors.newSingleThreadExecutor()) {
            ToolExecutionContext context = new ToolExecutionContext(
                    permission,
                    permissionContext,
                    hooks,
                    mock(ToolResultStorage.class),
                    event -> { },
                    () -> true,
                    ToolRuntimeContext.unavailable(),
                    toolPool,
                    Set.of(),
                    CancellationToken.NONE);

            ToolExecutionResult result = executor.execute(
                    List.of(call("call-1", descriptor.name())), context).getFirst();

            assertThat(result.error()).isTrue();
            assertThat(handlerCalls).hasValue(0);
        }
    }

    @Test
    void cancelsSubmittedBatchAndDoesNotConvertCancellationToToolError() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger secondStarted = new AtomicInteger();
        ToolDescriptor first = descriptor("first", invocation -> {
            firstStarted.countDown();
            try {
                releaseFirst.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new OperationCancelledException(CancellationReason.CALLER_ABORTED);
            }
            return ToolHandlerOutput.of("first");
        });
        ToolDescriptor second = descriptor("second", invocation -> {
            secondStarted.incrementAndGet();
            return ToolHandlerOutput.of("second");
        });
        ToolRegistry registry = registry(Map.of("first", first, "second", second));
        PermissionPolicy permission = mock(PermissionPolicy.class);
        when(permission.evaluate(any(), any())).thenReturn(PermissionDecision.allow("tool"));
        HookRegistry hooks = mock(HookRegistry.class);
        when(hooks.dispatch(any(), any())).thenReturn(HookResult.noop());
        RegistryToolExecutor toolExecutor = new RegistryToolExecutor(
                registry,
                permission,
                hooks,
                mock(ToolResultStorage.class),
                null,
                new ToolsProperties());
        CancellationSource cancellation = new CancellationSource();
        ExecutorService batchExecutor = Executors.newFixedThreadPool(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        ToolExecutionContext context = new ToolExecutionContext(
                permission,
                mock(PermissionContext.class),
                hooks,
                mock(ToolResultStorage.class),
                mock(ToolTraceSink.class),
                null,
                ToolRuntimeContext.unavailable(),
                batchExecutor,
                Set.of(),
                cancellation.token());
        List<ToolCall> calls = List.of(call("1", "first"), call("2", "second"));

        CompletableFuture<List<ToolExecutionResult>> result = CompletableFuture.supplyAsync(
                () -> toolExecutor.execute(calls, context), caller);
        assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
        cancellation.cancel(CancellationReason.CALLER_ABORTED);

        assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                .hasCauseInstanceOf(OperationCancelledException.class);
        assertThat(secondStarted).hasValue(0);
        releaseFirst.countDown();
        batchExecutor.shutdownNow();
        caller.shutdownNow();
    }

    private static ToolDescriptor descriptor(String name, ToolHandler handler) {
        return new ToolDescriptor(
                name, name, Map.of(), Map.of(), "", true, handler,
                ToolClassifier.defaultClassifier(), ToolInputValidator.noop(), ToolResultPolicy.defaults());
    }

    private static ToolCall call(String id, String name) {
        return new ToolCall(id, name, Map.of(), "conv", 1, "trace", null, Map.of());
    }

    private static ToolRegistry registry(Map<String, ToolDescriptor> descriptors) {
        return new ToolRegistry() {
            @Override public Optional<ToolDescriptor> get(String name) { return Optional.ofNullable(descriptors.get(name)); }
            @Override public List<ToolDescriptor> visibleDescriptors(ToolVisibilityContext context) { return List.copyOf(descriptors.values()); }
            @Override public List<Map<String, Object>> toolSchemas(ToolVisibilityContext context) { return List.of(); }
            @Override public List<String> toolPromptSections(ToolVisibilityContext context) { return List.of(); }
        };
    }
}
