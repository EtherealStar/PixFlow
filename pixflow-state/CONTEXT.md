# Execution State

This context provides recovery and progress read models without owning task writes, scheduling, or result persistence.

## Language

**Work Unit Checkpoint**:
The durable fact that a work unit completed successfully and may be skipped during recovery.
_Avoid_: node checkpoint, Redis cache entry

**Skippable Work Units**:
The set of successful work-unit identities that a recovering worker must not execute again.
_Avoid_: existing result rows, completed nodes

**Runtime Reference**:
A disposable reference to intermediate bytes stored outside Redis; it may reduce repeated work but never proves completion.
_Avoid_: checkpoint, cached image bytes
