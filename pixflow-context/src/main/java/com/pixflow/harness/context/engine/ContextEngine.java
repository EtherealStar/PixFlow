package com.pixflow.harness.context.engine;

import com.pixflow.harness.context.compaction.CompactionResult;
import com.pixflow.harness.context.compaction.CompactionTrigger;
import com.pixflow.harness.context.compaction.ContextCompactionService;
import com.pixflow.harness.context.runtime.CurrentModelContext;
import com.pixflow.harness.context.snapshot.ContextSnapshot;
import com.pixflow.harness.context.snapshot.PreparedContext;
import com.pixflow.harness.context.snapshot.ToolSchemaView;
import com.pixflow.harness.context.store.MessageStore;
import com.pixflow.harness.eval.model.TracePruneEntry;
import java.time.Instant;
import java.util.List;

/**
 * 提供「组装快照」一个入口：cheap pipeline + 必要时 destructive autoCompact + cheap pipeline 再准备。
 *
 * <p>本期把 cheap / destructive compaction 产生的 prune 裁剪日志以 {@link BuildResult#pruneEntries()} 形式
 * 一起回带出去（接口微调，参见 {@code harness/loop.md} §八末尾的设计建议）。这样 loop 可以经
 * {@code TraceFanout} 转投到 {@code eval} 的 {@code TurnTrace.recordPrune}，避免 context 反向依赖 eval。
 *
 * <p>旧签名 {@link #buildForModelLegacy(String, List)} 仍保留为 deprecated 路径，过渡期由 context 内部其他
 * 调用点继续走旧路径；新代码（包括 loop）应改用 {@link #buildForModel(String, List)}。
 */
public final class ContextEngine {
    private final MessageStore messageStore;

    private final ContextCompactionService compactionService;

    private final CurrentModelContext currentModelContext;

    public ContextEngine(
            MessageStore messageStore,
            ContextCompactionService compactionService,
            CurrentModelContext currentModelContext) {
        this.messageStore = messageStore;
        this.compactionService = compactionService;
        this.currentModelContext = currentModelContext;
    }

    /**
     * 完整的「组装快照 + 带回本轮 prune 裁剪条目」入口。新入口；loop 等下游消费者应使用本方法。
     *
     * <p>本轮发生的 cheap pipeline 裁剪、destructive compaction 触发都会把对应 {@link TracePruneEntry}
     * 累积到 {@link BuildResult#pruneEntries()}。如果本轮同时发生了 {@link CompactionTrigger#AUTO} 或
     * 之前由 {@code reactiveCompact} 写入 {@link CurrentModelContext} 的 transition，则该 transition
     * 也会被翻译为一条 prune 条目（phase = transition.trigger().name()）。
     */
    public BuildResult buildForModel(String systemPrompt, List<ToolSchemaView> toolSchemas) {
        PreparedContext prepared = compactionService.prepare(messageStore.currentMessages());
        CompactionResult transition = compactionService.maybeAutoCompact(messageStore, prepared).orElse(null);
        if (transition != null) {
            prepared = compactionService.prepare(messageStore.currentMessages());
        }
        ContextSnapshot snapshot = new ContextSnapshot(
                systemPrompt,
                prepared.messages(),
                toolSchemas,
                prepared.usageHints(),
                prepared.transcriptRefs(),
                transition);
        currentModelContext.set(snapshot);

        // 收集本轮裁剪条目。cheap pipeline 的 prune 走 PreparedContext.usageHints 推断
        // （目前仅在 transition 非空时才有显式来源）；destructive compaction 的 transition 转译为一条 prune。
        java.util.List<TracePruneEntry> entries = new java.util.ArrayList<>();
        if (transition != null) {
            entries.add(new TracePruneEntry(
                    Instant.now(),
                    transition.trigger().name(),
                    transition.tokenBefore(),
                    transition.tokenAfter(),
                    transition.summarized() ? "summarized" : (transition.fallback() ? "fallback" : "unknown"),
                    transition.metadata()));
        }
        Object cheapPrune = prepared.usageHints().get("microcompactedTokens");
        if (cheapPrune instanceof Number number && number.intValue() > 0) {
            entries.add(new TracePruneEntry(
                    Instant.now(),
                    CompactionTrigger.MICRO.name(),
                    -1,
                    number.intValue(),
                    "microcompact",
                    java.util.Map.of()));
        }
        return new BuildResult(snapshot, java.util.List.copyOf(entries));
    }

    /**
     * 兼容旧调用方的 deprecated 入口。新代码请改用 {@link #buildForModel(String, List)}。
     *
     * <p>该入口不返回 prune 裁剪条目；如果调用方需要保留裁剪可见性，请迁移到新入口。
     */
    @Deprecated
    public ContextSnapshot buildForModelLegacy(String systemPrompt, List<ToolSchemaView> toolSchemas) {
        BuildResult result = buildForModel(systemPrompt, toolSchemas);
        return result.snapshot();
    }

    /**
     * buildForModel 的伴随返回结构：除主 record {@link ContextSnapshot} 外，附带本轮 prune 裁剪条目列表。
     *
     * <p>{@link #snapshot()} 与旧 {@code buildForModel(...)} 的返回值形状一致；{@link #pruneEntries()}
     * 由 loop 转投 eval。
     */
    public record BuildResult(ContextSnapshot snapshot, List<TracePruneEntry> pruneEntries) {
        public BuildResult {
            snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
            pruneEntries = pruneEntries == null ? List.of() : List.copyOf(pruneEntries);
        }
    }
}
