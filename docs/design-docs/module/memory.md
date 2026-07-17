# module/memory — Read-only Memory Recall

## Responsibility

Memory provides read-only recall of existing user preferences, SKU history, and analysis insights for Agent prompt assembly. It owns recall planning, retrieval, ranking, token budgeting, degradation, and trace metadata.

The current product does not write conversation content, assistant conclusions, Proposal decisions, task results, user feedback, or Rubrics findings back into business memory.

## Domain model

| Term | Meaning |
|---|---|
| Existing Memory Fact | A preference, SKU-history row, or analysis insight already present in the configured data source. |
| Recall Signal | Prompt text, canonical Asset References, category hints, conversation phase, and other read-only context used to plan retrieval. |
| Memory Context | Ranked, budgeted sections returned to Agent prompt assembly. |
| Analysis Insight Index | A read-optimized Qdrant projection of existing ACTIVE analysis-insight facts. |

Session Memory is not business memory. It belongs to the Agent/context compression boundary, is scoped to one conversation, and cannot be promoted into user preferences, SKU history, or analysis insights.

## Public API

```java
public interface MemoryService {
    MemoryContext prepareContext(MemoryContextRequest request);
}
```

`MemoryContextRequest` contains:

```text
conversationId
turnNo
traceId
userPrompt
references[]        // canonical referenceKey + displayPathSnapshot
categoryHints[]
metadata
tokenBudget
```

It does not accept parallel material identities such as `packageId`, `skuIds`, `imageIds`, object-storage keys, or file paths. When a recall strategy needs a package/SKU/image fact, it resolves the canonical key through the shared Asset Reference Resolver.

The public API deliberately has no `ingestAsync`, `reinforce`, `promote`, preference upsert, or SKU-history append operation.

## Recall pipeline

```text
MemoryContextRequest
  -> extract non-authoritative recall signals
  -> resolve canonical Asset References when needed
  -> read existing user preferences
  -> read existing SKU history for resolved SKU identities
  -> search ACTIVE analysis insights by dense vector and MySQL FULLTEXT
  -> fuse rankings with RRF and apply existing lifecycle weights
  -> enforce per-section and total token budgets
  -> return MemoryContext plus RecallTrace
```

Recall is system-owned and runs before prompt assembly. It is not an Agent tool. The model cannot choose hidden records, alter filters, or request arbitrary memory writes.

### Sections

| Section | Source | Rule |
|---|---|---|
| `user_preferences` | existing `user_preference` rows | small full read; omit when empty |
| `sku_history` | existing `sku_history` rows | exact resolved SKU match; omit when no referenced SKU is relevant |
| `analysis_insights` | existing ACTIVE facts via vector/FULLTEXT retrieval | rank, deduplicate, and budget; omit on empty/degraded result |

Injected content carries concise provenance. Full candidate scores, filters, and degradation details go to `agent_trace.recall_json`, not into the model-visible prose.

## Storage boundary

- MySQL remains the fact source for existing preferences, SKU history, and analysis insights.
- Qdrant is a read-optimized projection of ACTIVE analysis insights; vector failure degrades to FULLTEXT.
- Online Agent, Task, Conversation, Hooks, and Rubrics paths have no write authority over these stores.
- Operational import/reindex of a prepared administrator dataset is outside the conversation product flow and must not be triggered by a user message or task completion.

## Removed mechanisms

The target design deletes, rather than deprecates behind a compatibility path:

- `MemoryService.ingestAsync(...)` and `MemoryService.reinforce(...)`;
- `InsightIngestService`, online extraction LLM calls, consolidation queues, and conflict/upsert workflows;
- `TURN_STOPPED`, `ASSISTANT_MESSAGE_COMPLETED`, or `TASK_COMPLETED` memory-write Hooks;
- automatic user-preference updates and automatic SKU-history appends;
- Rubrics Promotion into `analysis_insight` or SKU history;
- treating rejection/confirmation behavior as a remembered preference;
- any Agent-visible memory-write or `recall_memory` tool.

No renamed equivalent may reintroduce these flows without a new explicit product decision and design update.

## Lifecycle

Existing rows may already carry `ACTIVE`, `SUPPRESSED`, or `EXPIRED` state. Online recall reads only ACTIVE facts. This module does not infer new lifecycle transitions from conversations, task outcomes, access count, or model output.

Administrative expiration/reindex jobs may maintain a preloaded dataset, but they are operational data maintenance, not product memory writeback. They must be independently auditable and cannot be called from Agent/Task/Conversation paths.

## Errors and degradation

| Failure | Behavior |
|---|---|
| Reference cannot be resolved | Ignore that recall signal and record a trace reason; message/proposal validation remains the owning boundary. |
| Qdrant unavailable | Use MySQL FULLTEXT for analysis insights. |
| FULLTEXT unavailable | Use vector results if available. |
| MySQL fact source unavailable | Return an empty or partial Memory Context and report a dependency failure; do not block the conversation. |
| Token budget exhausted | Preserve the most relevant small sections and omit lower-ranked insights. |

Recall never mutates facts as a side effect of reading them: no access-count increment, reinforcement timestamp, decay update, or implicit cache-backed write.

## Module contracts

| Consumer/provider | Contract |
|---|---|
| `agent` | Calls `prepareContext` before prompt assembly and injects returned sections; performs no memory writes. |
| `module/file` | Provides canonical Asset Reference resolution used only to derive read filters. |
| `infra/vector` | Executes read-only ACTIVE-insight search. |
| `infra/ai` | Provides query embedding only; no extraction/consolidation model call. |
| `harness/eval` | Records recall plan, candidates, selected items, filters, and degradation in trace. |
| `module/rubrics` | Has no memory dependency or Promotion route. |

Memory does not depend on Hooks, Agent, Task, DAG, Conversation, or Rubrics.

## Verification

- public API contains only `prepareContext`;
- architecture tests reject imports of removed ingest/reinforce/Hook types;
- canonical references resolve to the expected read filters, while legacy identity fields are rejected;
- retrieval fusion is deterministic for equal inputs;
- all retrieval paths are side-effect free;
- vector/FULLTEXT failure combinations return documented partial or empty contexts;
- Agent-visible tools contain neither recall nor memory-write tools.

## Revision Notes

- 2026-07-17 / Codex: changed module/memory to read-only recall and removed conversation/task/Rubrics writeback, consolidation Hooks, ingest/reinforce APIs, and automatic fact mutation.
