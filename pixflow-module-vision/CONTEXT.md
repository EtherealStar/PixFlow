# Product Vision Understanding

This context turns product images into current, durable, observation-only facts that other contexts can use without asking a vision model to reason about business outcomes.

## Language

**Product Visual Facts**:
Structured facts that are directly observable in product images, together with limitations and conflicts. They do not include marketing claims, inferred product properties, or generation instructions.
_Avoid_: Product understanding, visual description text, product copy

**SKU Visual Facts**:
The one current Product Visual Facts document for an SKU within one Asset Package, based on a bounded sample of that SKU's current Original Images. It is replaced rather than versioned when an administrator edits it or a later analysis succeeds.
_Avoid_: SKU Visual Snapshot, SKU profile, vision summary

**Image Visual Facts**:
The one current Product Visual Facts document for a specific source image, used when a task depends on that image's exact view, composition, text, or background.
_Avoid_: Image Visual Snapshot, image caption, redraw analysis

**Visual Analysis Work Item**:
The one current recoverable unit of work for an SKU or source image. A new analysis generation resets its provider-attempt budget without creating historical work items.
_Avoid_: Vision task, copy enrichment task

**Visual Input Fingerprint**:
The identity of the current image bytes from which Product Visual Facts are derived. Only an image add, delete, replacement, or content-hash change changes this fingerprint; model and analysis-contract changes do not.
_Avoid_: Cache key, analysis version, display name

**Observation Limitation**:
A boundary on what the analyzed views can support, such as a missing rear view or an unreadable label.
_Avoid_: Disclaimer, low confidence

**Observation Conflict**:
Two or more visible observations that cannot be conservatively represented as one fact.
_Avoid_: Model error, inconsistency

**Visual Facts Lookup**:
The main Agent's read path for current Product Visual Facts. It returns the same current facts regardless of whether they were last written by the visual model or the administrator.
_Avoid_: Vision subagent, visual question answering
