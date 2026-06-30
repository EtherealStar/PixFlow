package com.pixflow.harness.loop;

import com.pixflow.harness.context.budget.ConservativeTokenEstimator;
import com.pixflow.harness.context.budget.ContextBudgetConfig;
import com.pixflow.harness.context.budget.ContextBudgetService;
import com.pixflow.harness.context.compaction.CompactionConfig;
import com.pixflow.harness.context.compaction.ContextCompactionService;
import com.pixflow.harness.context.engine.ContextEngine;
import com.pixflow.harness.context.runtime.CurrentModelContext;
import com.pixflow.harness.context.store.MessageStore;
import com.pixflow.harness.loop.config.LoopProperties;
import com.pixflow.harness.loop.permission.DefaultPermissionContextFactory;
import java.util.Objects;

/**
 * 测试用 AgentLoop 工厂：把所有 fake 拼装起来。
 */
final class LoopTestSupport {

    private LoopTestSupport() {
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        RuntimeState state;
        MessageStore store = new MessageStore();
        FakeChatModelClient modelClient = new FakeChatModelClient();
        com.pixflow.infra.ai.resilience.ModelRetryRunner retryRunner = FakeModelRetryRunner.noRetryRunner();
        FakeToolExecutor toolExecutor = new FakeToolExecutor();
        FakeHookRegistry hookRegistry = new FakeHookRegistry();
        InMemoryTraceRecorder traceRecorder = new InMemoryTraceRecorder();
        RecordingErrorRecorder errorRecorder = new RecordingErrorRecorder();
        LoopProperties properties = new LoopProperties();
        String conversationId = "conv-test";

        Builder state(RuntimeState state) {
            this.state = state;
            return this;
        }

        Builder properties(LoopProperties p) {
            this.properties = p;
            return this;
        }

        Builder conversationId(String id) {
            this.conversationId = id;
            return this;
        }

        Builder modelClient(FakeChatModelClient c) {
            this.modelClient = c;
            return this;
        }

        Builder retryRunner(com.pixflow.infra.ai.resilience.ModelRetryRunner r) {
            this.retryRunner = r;
            return this;
        }

        Builder toolExecutor(FakeToolExecutor t) {
            this.toolExecutor = t;
            return this;
        }

        AgentLoop build() {
            Objects.requireNonNull(state, "state required");
            state.setConversationId(conversationId);
            ContextBudgetService budgetService = new ContextBudgetService(
                    ContextBudgetConfig.defaults(), new ConservativeTokenEstimator(), null);
            ContextCompactionService compactionService = new ContextCompactionService(
                    budgetService, new ConservativeTokenEstimator(), null,
                    CompactionConfig.defaults());
            ContextEngine contextEngine = new ContextEngine(store, compactionService, new CurrentModelContext());
            return new AgentLoop(
                    state,
                    store,
                    contextEngine,
                    compactionService,
                    modelClient,
                    retryRunner,
                    toolExecutor,
                    null,                       // permissionPolicy
                    null,                       // resultStorage
                    null,                       // planModeView
                    hookRegistry,
                    traceRecorder,
                    new DefaultPermissionContextFactory(),
                    errorRecorder,
                    properties);
        }
    }
}