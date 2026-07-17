# Task Execution

This context owns asynchronous task scheduling, work-unit outcomes, terminal-state decisions, and explicit retries of terminal tasks.

## Language

**Work Unit**:
The smallest independently scheduled and checkpointed task component: either an image branch, a group branch, or one generative image operation.
_Avoid_: node, provider attempt

**Work Unit Identity**:
The stable identity of a work unit within a task, derived from its kind, member, and deterministic branch identity.
_Avoid_: result row id, object key

**Execution Attempt**:
One execution of a work unit under the task's current execution epoch.
_Avoid_: node attempt, retry task

**Partial Task**:
A terminal task containing at least one successful work unit and at least one failed or skipped work unit.
_Avoid_: completed with warnings

**Derived Retry Task**:
A new task created from the failed work units of a terminal source task while preserving that source task's immutable history.
_Avoid_: reopened task, resumed terminal task

**Execution Epoch**:
A monotonically increasing ownership generation that prevents an obsolete worker from committing work-unit outcomes or task terminal state.
_Avoid_: lock token, task attempt count

**Business Idempotency Identity**:
The stable domain identity that ensures one authorized action creates at most one task, such as a Proposal identity or a source-task retry identity.
_Avoid_: random HTTP idempotency key

**Published Asset**:
A successful task image result that the Asset Library has accepted as a Generated Image with its own identity.
_Avoid_: process result, temporary output

**Activity Record**:
The user-facing projection of a task's lifecycle, removable independently of a Published Asset.
_Avoid_: task aggregate, generated image

**Retention Cleanup**:
Lifecycle deletion of terminal execution records and unconsumed temporary outputs without reopening or rewriting the task's recorded outcome.
_Avoid_: retry, terminal-state mutation
