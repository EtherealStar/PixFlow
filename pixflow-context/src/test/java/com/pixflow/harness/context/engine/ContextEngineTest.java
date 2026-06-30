package com.pixflow.harness.context.engine;

import com.pixflow.harness.context.budget.ConservativeTokenEstimator;
import com.pixflow.harness.context.budget.ContextBudgetConfig;
import com.pixflow.harness.context.budget.ContextBudgetService;
import com.pixflow.harness.context.compaction.CompactionConfig;
import com.pixflow.harness.context.compaction.ContextCompactionService;
import com.pixflow.harness.context.runtime.CurrentModelContext;
import com.pixflow.harness.context.snapshot.ContextSnapshot;
import com.pixflow.harness.context.snapshot.ToolSchemaView;
import com.pixflow.harness.context.store.MessageStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextEngineTest {
    @Test
    void buildForModelStoresCurrentSnapshot() {
        MessageStore store = new MessageStore();
        store.appendUser("hello");
        ConservativeTokenEstimator estimator = new ConservativeTokenEstimator();
        ContextCompactionService compactionService = new ContextCompactionService(
                new ContextBudgetService(ContextBudgetConfig.defaults(), estimator, null),
                estimator,
                null,
                CompactionConfig.defaults());
        CurrentModelContext holder = new CurrentModelContext();
        ContextEngine engine = new ContextEngine(store, compactionService, holder);

        ContextSnapshot snapshot = engine.buildForModelLegacy("system", List.of(new ToolSchemaView("tool", "desc", Map.of())));

        assertThat(snapshot.systemPrompt()).isEqualTo("system");
        assertThat(holder.snapshot()).contains(snapshot);
    }
}
