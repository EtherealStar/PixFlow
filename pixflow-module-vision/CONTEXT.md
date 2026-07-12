# Product Vision Understanding

This context turns product images into durable, observation-only facts that other contexts can use without asking a vision model to reason about business outcomes.

## Language

**Product Visual Facts**:
Structured facts that are directly observable in product images, together with limitations and conflicts. They do not include marketing claims, inferred product properties, or generation instructions.
_Avoid_: Product understanding, visual description text, product copy

**SKU Visual Snapshot**:
The current Product Visual Facts for one SKU within one asset package, based on a bounded sample of that SKU's images.
_Avoid_: SKU profile, vision summary

**Image Visual Snapshot**:
The Product Visual Facts for one specific source image, used when a task depends on that image's exact view, composition, text, or background.
_Avoid_: Image caption, redraw analysis

**Visual Analysis Work Item**:
A recoverable unit of work that produces either an SKU Visual Snapshot or an Image Visual Snapshot for a specific visual input. Delivery retries and provider attempts remain part of the same work item.
_Avoid_: Vision task, copy enrichment task

**Visual Input Fingerprint**:
The identity of the images and analysis contract from which a Visual Snapshot is derived. A changed fingerprint means the previous snapshot is historical rather than current.
_Avoid_: Cache key, image hash

**Observation Limitation**:
A boundary on what the analyzed views can support, such as a missing rear view or an unreadable label.
_Avoid_: Disclaimer, low confidence

**Observation Conflict**:
Two or more visible observations that cannot be conservatively represented as one fact.
_Avoid_: Model error, inconsistency

**Visual Facts Lookup**:
The main Agent's read path for current Product Visual Facts. It may complete missing analysis, but never exposes historical facts as current facts.
_Avoid_: Vision subagent, visual question answering
