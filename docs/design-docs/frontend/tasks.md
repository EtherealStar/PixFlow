# Global Activity Frontend Design

## Responsibility

Activity is a global administrator-scoped projection of uploads, extraction jobs, deterministic processing tasks, and IMAGEGEN tasks. It exists on every authenticated route and is independent of the current conversation.

## Recovery and events

Startup and reconnect load a 1-based paginated Activity snapshot. One administrator event stream supplies subsequent upserts and removals. Sequence/cursor information prevents stale events from overwriting newer snapshots. A reconnect performs snapshot reconciliation before consuming live changes.

Activity cards contain `activityId`, kind, status, progress, owning conversation when present, related package/task IDs, timestamps, and allowed actions. They never infer state from local task objects. A card may be expanded inside the panel; there is no standalone task-detail page.

## Status models

Upload activity uses Uploading, Extracting, Succeeded, and Failed. Hash calculation belongs to Uploading. Interrupted client uploads are presented as a recoverable failed/interrupted condition until the same archive is selected again; Paused is not a user-visible status.

IMAGEGEN uses Generating, Succeeded, and Failed while present. Provider retries stay Generating. Cancellation uses an internal cancelling phase; after backend terminal cleanup the activity is removed rather than retained as a cancelled record. IMAGEGEN cannot be Partial because one task produces one image.

Deterministic batch processing uses Queued/Running, Succeeded, Partially succeeded, Failed, and cancellation cleanup. Partially succeeded shows succeeded/failed counts and Retry failed items.

## Actions and lifecycle

The panel never auto-expands. Collapsed badges summarize running, succeeded, and failed counts. Failures and completion of all newly active work may each produce one global toast.

Card details expose Cancel only while the backend accepts it. Retry failed items creates a derived retry and never reopens the terminal source task. Conversation, package, and output links are explicit actions.

The Web addresses Cancel, Retry failed items, and Clear to an `activityId`
through the Activity endpoints in `api.md`. It does not call Task-specific
command URLs or infer a command from `taskId`. App routes the Activity Command
to File or Task, and the owner rechecks state, authorization, and idempotency.
This keeps one frontend command surface without moving Task retry semantics into
the Activity projection.

Clearing successful activity deletes its Activity and execution history but never successful output images. Clearing a failed task immediately removes its activity, execution data, temporary objects, and failed artifacts. Otherwise failed task data expires after 24 hours. Cancelling removes only objects produced by that task and never source packages, source images, or another task's outputs.

The derived-retry business key is `retry-failed:{sourceTaskId}`. Repeated actions for the same direct source snapshot resolve to the same child task.

## Invariants

1. Activity is global, not current-conversation state.
2. Proposals are conversation decisions and never Activity items.
3. Terminal source tasks stay immutable; retry creates a child.
4. Successful asset lifetime is independent of Activity and execution-record lifetime.
