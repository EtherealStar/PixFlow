---
status: accepted
---

# Use work-unit checkpoints instead of node checkpoints

PixFlow records successful image branches and group branches as the durable recovery unit. Provider attempts remain observable and may retry inside their owning boundary, but DAG nodes do not persist independent checkpoints; after a worker crash, successful work units are skipped and every other selected work unit is recomputed as a whole. This preserves the decode-once/encode-once pipeline and avoids a second workflow state machine for node outputs, dependency invalidation, and intermediate-object cleanup.
