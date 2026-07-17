# Chat Frontend Design

## Responsibility

Chat owns conversation history, live Agent timeline rendering, the Composer, structured mentions, per-conversation in-memory queues, Proposal cards, and background-conversation notifications.

## History and live timeline

Entering a conversation loads the newest history page. Scrolling to the top requests older pages. Backend `messageId` is the merge and deduplication identity for history and `assistant_message_completed` events.

The live reducer consumes assistant deltas, assistant completion, user-readable tool status, relevant transitions, `proposal_ready`, errors, and turn completion. Starting a new turn never clears prior history or completed timeline items. Tool events render only product-language summaries; raw tool names, arguments, keys, and results stay server-side.

SSE interruption triggers a latest-history reconciliation. A matching completed message replaces the live partial item. If no complete message exists, received text remains and is marked interrupted. The client does not automatically resend the prompt.

## Composer and mentions

The Composer is a structured editor. Mention tokens are atomic nodes backed by `referenceKey` and `displayPathSnapshot`; text around them remains normal editable prompt text. The picker behavior and key contract are defined by `../base/asset-references.md`.

The paperclip opens a multi-file picker restricted to `.zip,.rar,.7z`. Drag-and-drop uses the same archive validation. Images and other invalid file types are rejected before upload with `只能上传压缩文件`. Upload progress never appears inside the Composer.

A message can select at most 20 exact references. The send request preserves token order. A non-terminal package blocks dispatch with exactly `素材处理中，请点击右侧任务栏查看进度`.

## Per-conversation queue

Queue ownership is application-level and keyed by conversation ID. Each queue is FIFO with capacity five. Different conversations may each have one active turn and one queue.

The queue panel is fixed immediately above the Composer. Each item shows a text summary, mention tokens, Edit, and Cancel. Editing retains FIFO position. The active head is dispatched directly after the current turn finishes; no user-visible Sending state is added. If the head is being edited, dispatch waits.

Normal turn completion and user Stop advance the queue. Network, SSE, and server failures pause it until Continue. A failed or deleted asset reference pauses that item for editing. Queue state survives SPA navigation but not reload, tab close, site exit, process exit, or another tab.

Pending Proposals pause automatic dispatch for their conversation. The Composer may still add items, labelled Waiting for proposal decisions. When all pending Proposals are confirmed or rejected, normal FIFO dispatch resumes.

## Proposal cards

`proposal_ready` adds or updates a card by `proposalId`. Multiple cards may belong to one turn. Controls remain disabled until the turn emits `completed`, then every card is independently actionable.

Confirm is one click and sends only the proposal identity. It does not request a challenge, collect a fixed phrase, or generate a random idempotency key. The server treats `proposalId` as the business idempotency identity and returns the created task.

Reject collects no reason. A successful rejection removes the card. The transcript does not receive a synthetic rejection message. Rejection ends only that proposal flow; the user is not forced to send another prompt.

When a proposal is created in a background conversation, the application emits one clickable toast per completed turn and shows the pending count on that conversation until every card is resolved. Proposal cards never appear in Activity.

Proposal cards and counts survive SPA route changes, including navigation to Materials or Outputs, but are not persisted to localStorage, IndexedDB, or the backend. Reload, tab close, and process exit do not restore unresolved cards.

## Multi-tab behavior

The backend conversation lock is authoritative. If another tab owns the active turn, this tab shows `该会话正在其他页面运行`. It cannot abort, replace, or enqueue into the other tab's in-memory queue.

## Invariants

1. Message history is the durable conversation fact source; live timeline state is a presentation projection.
2. Queue state and live runtimes are not persisted.
3. Proposal validation completes before `proposal_ready` reaches the frontend.
4. No frontend component parses display text to recover a reference key.
