# Web Workspace

This context owns the current browser application's interaction state and projections.

## Language

**Materials**:
The browse surface for Asset Packages, SKU scopes, and Original Images.
_Avoid_: files, outputs

**Product Visual Analysis**:
The Materials detail surface for viewing and editing the one current Product Visual Facts document shared by all Original Images in the same SKU scope.
_Avoid_: image analysis, visual summary, SKU Visual Snapshot

**Outputs**:
The browse surface for Generated Images grouped by their conversation and task lineage.
_Avoid_: task history, materials

**Mention Token**:
An atomic Composer element bound to one canonical Asset Reference.
_Avoid_: parsed @ text, attachment chip

**Queued Message**:
A user message waiting in the current browser application for its conversation's active Agent Turn to end.
_Avoid_: backend message queue, draft history

**Activity**:
The global user-facing projection of upload, extraction, deterministic processing, and redraw work.
_Avoid_: Proposal, task domain entity

**Activity Record**:
One removable item in Activity; its lifetime is independent of any successfully published Generated Image.
_Avoid_: output asset
