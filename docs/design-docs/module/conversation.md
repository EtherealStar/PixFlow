# module/conversation — Conversations, Turns, References, and Proposals

## Responsibility

Conversation owns conversation lifecycle, user-message admission, per-conversation turn exclusion, Agent SSE delivery, history read APIs, and the user decision boundary for Proposals. Durable message writes still flow through the session/context MessageStore boundary.

Conversation does not upload files, expand packages in the Web client, execute image operations, own task workers, or persist browser message queues.

## Conversation lifecycle

Conversation records contain identity, owner, title, and timestamps. There is no archive state or archived-list query.

`DELETE /api/conversations/{conversationId}` is a real delete. It is rejected while the conversation owns an active Agent turn or non-terminal task. On success it removes conversation messages, any ephemeral Proposal runtime, conversation-only activity, and locks. It does not delete original Materials or successful generated images; those lifecycles belong to File/Task.

## Message submission

The JSON command contains prompt plus ordered resource references:

```json
{
  "prompt": "...",
  "references": [
    { "referenceKey": "package:123", "displayPathSnapshot": "summer.zip" }
  ]
}
```

The shared key grammar and expansion semantics are defined in `../base/asset-references.md`. Conversation validates count <= 20, canonical key parsing, ownership, availability, and display snapshots. It persists key plus snapshot as message metadata and passes typed references to the Agent. It never accepts legacy top-level `packageId`, `attachments`, `UPLOAD_IMAGE`, `PACKAGE_REFERENCE`, multipart data, file bytes, or object-storage paths.

The model context renders reference snapshots and canonical keys and states that File/processing tools accept the same keys. PACKAGE/SKU expansion occurs only inside backend resolvers at inspection, proposal validation, or task planning boundaries.

## Turn exclusion and multi-tab behavior

One conversation may own one active Agent turn. A distributed lock covers preparation, SSE ownership, cancellation, and terminal release. Lock acquisition failure returns the ordinary busy result rendered as `该会话正在其他页面运行`; another tab receives no authority to stop or queue work for the owner.

Different conversations may run concurrently. Browser queues are frontend application-memory state and do not enter Conversation tables or Redis.

## SSE contract

The message POST returns named SSE events defined in `frontend/api.md`. Conversation forwards safe Agent events and assigns durable message IDs through MessageStore.

Tool events are projected to `tool_status` before leaving the server. The projection contains a product-language status only. Raw tool names, arguments, reference keys, and full results remain in server traces.

`assistant_message_completed` carries the stored message ID. `completed` is the turn terminal event. Disconnect cancels delivery according to the turn runtime, but the frontend repairs presentation from history and never replays the message command automatically.

## Proposal publication

A Proposal is a validated request for one user-authorized side effect. The Agent may create several Proposals in one turn. Each has its own `proposalId` and lifecycle.

Before a Proposal is visible, the owning proposal service performs:

1. schema and structural validation;
2. canonical reference parsing and permission checks;
3. current resource/readiness checks;
4. reference expansion and work-unit deduplication;
5. task-specific count and capability validation;
6. payload hash/canonicalization checks.

Technical validation failures are returned as tool errors to the Agent in the same turn. The Agent may correct and resubmit. A conflict between the user's request and material facts is not auto-corrected; the Agent asks through normal conversation.

Only validated Proposals enter the active conversation runtime and emit `proposal_ready`. Pending Proposal payloads are not written to MySQL, Redis, message metadata, or a `pending_plan` table. The frontend keeps cards only in the current SPA runtime, may display them immediately, and enables decisions only after `completed`.

## Direct decision API

```text
POST /api/conversations/{conversationId}/proposals/{proposalId}/confirm
POST /api/conversations/{conversationId}/proposals/{proposalId}/reject
```

Confirm/reject bodies are empty. There is no public challenge endpoint, challenge record, challenge answer, batch-threshold prompt, confirmation token, or client-generated `Idempotency-Key`.

`proposalId` is the sole business idempotency identity. Confirm performs ownership, current hash/count, permission, canonical payload, and pending-state CAS checks in one service transaction. The first success creates exactly one task and stores the resulting task ID; a replay returns that task.

Reject deletes the ephemeral Proposal and stops that proposal flow. It creates no user-visible message, standalone reason, context injection, or durable rejection audit row: the transcript already contains the Agent's own proposal output. A repeated reject is treated as successful even when the ephemeral value has already disappeared. Expiration also deletes the value without context injection.

Confirm removes the ephemeral Proposal after task binding. The Task/Activity record is the durable execution link; Proposal cards themselves are not durable history. Reload, tab close, or process exit does not restore unresolved cards. SPA route navigation may retain them because it does not destroy the application runtime.

## IMAGEGEN publication limit

One IMAGEGEN Proposal references one concrete source IMAGE and produces one image. PACKAGE/SKU expansion may therefore yield several independent Proposals. A turn publishes at most 20 IMAGEGEN Proposals. Above that limit the proposal tool returns a non-terminal validation result containing the count, and the Agent asks the user to choose a narrower SKU or image set. There is no bulk-confirm endpoint.

## History

History is 1-based paginated and newest-first at the API boundary. Each view exposes stable `messageId`, role, safe content, persisted reference snapshots, and timestamps. The frontend reverses/merges pages for chronological presentation and deduplicates against live completion events.

## Invariants

1. MessageStore/session remains the durable message writer.
2. A Proposal is never visible before complete backend validation.
3. User confirmation is one public action; defense-in-depth checks remain backend-internal.
4. `proposalId`, not an HTTP retry header, prevents duplicate execution.
5. Browser queues are never persisted by Conversation.
