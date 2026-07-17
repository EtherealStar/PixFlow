# module/imagegen — Single-image Redraw Capability

## Responsibility

Imagegen validates one-image redraw Proposals and executes one stateless redraw call. Task owns asynchronous scheduling, retries, cancellation, progress, recovery, and result persistence. File owns promotion of a successful generated result into a first-class generated image.

## Single-image invariant

One IMAGEGEN Proposal contains exactly one concrete IMAGE reference key, one canonical redraw prompt, and one parameter object. It creates one IMAGE_GEN task with one GENERATIVE work unit and may publish at most one generated image.

There is no `source_image_ids`, `sourceImageIds`, batch size up to 200, image fan-out inside one Proposal, partial terminal state, or bulk IMAGEGEN confirmation. These legacy contracts must be removed rather than supported in parallel.

PACKAGE and SKU keys may be expanded before Proposal creation. The Agent creates one independent Proposal per expanded source image, subject to the Conversation limit of 20 visible IMAGEGEN Proposals per turn. Above 20, no Proposal is published and the user is asked to narrow the selection.

## Proposal validation

Before `proposal_ready`, validation checks:

1. canonical IMAGE key and current administrator ownership;
2. source availability and processable image metadata;
3. source/package relationship and supported content type;
4. normalized prompt and parameter schema;
5. payload hash and proposal identity uniqueness.

Technical failures return to the Agent tool so it can correct the proposal in the same turn. User-intent conflicts are surfaced through conversation.

The canonical payload stores the one source reference key and resolved source image identity. The frontend never submits source IDs during confirm.

## Execution

After direct one-click confirmation, Conversation creates one IMAGE_GEN task. Task calls `ImageGenExecutor.redraw` with a resolved source descriptor and canonical prompt. Provider retries are bounded and silent at the owning AI/task boundary; Activity remains Generating until success or retry exhaustion.

The executor writes an epoch-scoped candidate object. Task fencing decides whether it becomes the successful work-unit output. A late or cancelled attempt cannot publish a valid asset.

On fenced success, File registers a new GENERATED Asset Image with a new `imageId`, inherited package/SKU, and source task/result/image lineage. Activity and task history may be cleared later without deleting that image.

## Cancellation and cleanup

Cancellation is cooperative. An in-flight provider call may finish naturally, but its result cannot win publication after cancellation. Terminal cleanup removes candidate/generated objects, temporary files, and intermediate data owned by that task. Source assets and other tasks' outputs are never deleted.

IMAGEGEN Activity has Generating, Succeeded, and Failed while present. It has no Partial state. Cancelled activity is removed after cleanup.

## Failure lifecycle

Provider failures remain internal during silent retries. Retry exhaustion records one safe final failure. The user may delete failed task data immediately; otherwise failed records and failed artifacts expire after 24 hours.

## Invariants

1. One Proposal, one source image, one task, one work unit, at most one generated image.
2. Imagegen owns no queue, progress store, task table, or recovery scanner.
3. A generated image always receives a new image ID.
4. Cancellation can never publish a valid output.
