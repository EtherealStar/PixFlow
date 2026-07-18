# Frontend State and Runtime Ownership

## Application-scoped state

- Auth runtime: in-memory access token, user, bootstrap, refresh, login, logout.
- Conversation runtime registry: one active Agent turn and FIFO queue per conversation.
- Activity runtime: snapshot cursor, paginated records, one STOMP subscription.
- UI store: panel/drawer state, network state, toasts, and non-sensitive pinned preferences.

These runtimes are mounted above RouterView so SPA navigation does not destroy them.

## Page-scoped state

Materials, Outputs, search forms, paging cursors, gallery selection, previews, image-detail navigation order, Product Visual Analysis draft/base/version/analysis-generation/status, and rename dialogs are page state. Materials keeps its originating gallery projection alive while navigating to an image-detail child route so Back can restore search, filters, sort, page and scroll position.

The Composer draft is scoped to its conversation runtime while the SPA is alive. Queued messages and their mention tokens are application-memory only. No queue or draft is restored in another tab or after reload.

## Durable fact sources

- Message history API owns durable transcript content.
- Proposal cards are current-SPA runtime state only; there is no durable recovery source.
- Activity REST snapshot plus event cursor owns long-running status recovery.
- Materials and Outputs APIs own asset availability and names.
- Vision current-facts API owns Product Visual Facts, analysis status, writer label, optimistic-concurrency version and update time.
- Auth refresh cookie owns login recovery.

Pinia maps, local indexes, and component arrays are projections, never substitutes for these sources.

## Runtime boundaries

Agent reducer owns SSE parsing results, live timeline, proposal events, interruption reconciliation, Stop, and queue advancement. Upload runtime owns hash, chunk scheduling, resume, and cancellation. Activity runtime owns global work projection and commands.

Stores expose state and intent methods. Vue components do not open transports, expand reference keys, invent idempotency keys, or orchestrate retries. The Materials detail runtime owns the two-second active-analysis poll, request cancellation on SKU change, reanalysis click identity, dirty-state guards and stale-response rejection.

## Persistence policy

Allowed local persistence is limited to non-sensitive panel preferences and small upload-session hints. Access tokens, message queues, Proposal cards, Product Visual Analysis drafts, file bytes, chunks, pre-signed URLs, and task execution state are never persisted by the frontend.
