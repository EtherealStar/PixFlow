---
status: accepted
---

# Retry terminal tasks by creating derived tasks

PixFlow keeps `COMPLETED`, `PARTIAL`, `FAILED`, and `CANCELLED` tasks and their work-unit results immutable. Retrying failed work units creates a new task that references the terminal source task and snapshots its failed work-unit identities; this preserves an auditable history and avoids reopening rows whose progress events, result objects, and terminal notifications may already have been consumed.

Immutability applies while an execution record is retained; lifecycle deletion is not a state transition or retry. Users may clear terminal Activity/execution records according to retention policy. Successfully published Generated Images have an independent asset lifecycle and are never deleted merely because their source task record is cleared.
