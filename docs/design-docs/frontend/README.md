# PixFlow Web Design Index

`docs/design-docs/frontend/` is the authority for PixFlow Web product behavior, frontend state, and frontend-facing HTTP/event contracts. `../web.md` is a compatibility entry point only. Current design is stated as target behavior; historical defect reports and execution progress do not belong in these documents.

## Documents

| Document | Authority |
|---|---|
| `product.md` | User-visible information architecture, workflows, queueing, mentions, proposals, uploads, Activity, responsive behavior, and authentication. |
| `api.md` | Target frontend-facing REST, SSE, and activity-event contract, including public request/response DTOs, errors, pagination, and removed routes. |
| `shell-routing-auth.md` | Application shell, routes, responsive panels, and in-memory access-token lifecycle. |
| `chat.md` | History, streaming timeline, per-conversation queues, mentions, and Proposal interaction. |
| `files.md` | Separate Materials and Outputs pages, image detail navigation, Product Visual Analysis editing, deletion, naming, and pagination. |
| `tasks.md` | Global Activity and task/result lifecycle. |
| `upload.md` | Archive-only chunk upload, resume, extraction, cancellation, and concurrency. |
| `transport-api.md` | HTTP normalization, SSE reconciliation, and administrator activity-stream behavior. |
| `stores-runtime.md` | Ownership of application-memory state and runtimes. |
| `ui-visual.md` | Visual tokens, accessibility, responsive constraints, and status presentation. |

Shared resource identity is defined in `../base/asset-references.md`. Backend details remain authoritative in the matching `module/`, `agent/`, `infra/`, and `harness/` documents.

## Global invariants

1. Frontend pagination is 1-based. An adapter may translate a backend implementation internally, but pages and stores never expose 0-based pagination.
2. Display names, object-storage paths, and local indexes are not resource identities.
3. The client never expands a package into image IDs for execution.
4. Long-running state comes from backend snapshots plus the transport defined by its owner: Activity and Agent use event streams, while Product Visual Analysis uses bounded polling only while detail is open. Component-local state is not a recovery source.
5. Message queues are intentionally application-memory only and are not restored after reload or in another tab.
6. There is no registration, Settings, Rubrics/evaluation center, landing page, or standalone task-detail route.
