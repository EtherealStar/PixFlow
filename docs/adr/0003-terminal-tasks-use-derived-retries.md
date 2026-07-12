---
status: accepted
---

# Retry terminal tasks by creating derived tasks

PixFlow keeps `COMPLETED`, `PARTIAL`, `FAILED`, and `CANCELLED` tasks and their work-unit results immutable. Retrying failed work units creates a new task that references the terminal source task and snapshots its failed work-unit identities; this preserves an auditable history and avoids reopening rows whose progress events, result objects, and terminal notifications may already have been consumed.
