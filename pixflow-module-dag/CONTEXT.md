# DAG Execution

This context turns an approved image plan into deterministic, independently executable branches while keeping scheduling and persistence outside the context.

## Language

**Image Plan**:
The user-confirmed DAG describing allowed image and copy transformations.
_Avoid_: compiled pipeline, workflow instance

**Canonical DAG**:
The normalized, immutable representation of an image plan used for hashing, persistence, and deterministic recompilation.
_Avoid_: raw Agent JSON

**Typed Execution Plan**:
The deterministic result of compiling a canonical DAG into typed external, local-image, group, and copy steps.
_Avoid_: parsed JSON, node map

**Branch**:
A deterministic source-to-sink path for one image member, identified by a stable branch identity.
_Avoid_: node attempt, task

**Group Branch**:
A branch that transforms a set of members and combines them into one result.
_Avoid_: batch, multi-image task

**Node Attempt**:
One try at an external boundary operation inside a work unit; it is observable but is not a checkpoint.
_Avoid_: node checkpoint, recovered node
