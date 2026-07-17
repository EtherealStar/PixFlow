# module/vision - Product Vision Understanding

> Authority: this document defines PixFlow's product-image understanding boundary. Domain terms are defined in `pixflow-module-vision/CONTEXT.md`; the system-wide boundary decision is recorded in `docs/adr/0004-use-durable-product-visual-facts.md`.
>
> Scope: after asset extraction, analyze a bounded sample of each `(packageId, skuId)` through the existing OpenAI-compatible vision role, persist observation-only facts, and expose those facts to the main Agent through one lookup tool. Marketing copy and redraw prompts are not vision-model outputs.

## 1. Purpose and invariants

`module/vision` owns durable Product Visual Facts. It does not own a child Agent, general visual question answering, marketing copy, image-generation prompts, or image generation.

The following invariants are mandatory:

1. Product Visual Facts contain only what is directly observable in the submitted image views. Apparent material is recorded as appearance, not as an unverified physical property; unseen properties remain absent or become an Observation Limitation.
2. Facts are scoped by asset package and SKU. A `skuId` is not globally unique across packages.
3. SKU and image snapshots are durable MySQL facts. They are not Qdrant memories and do not participate in automatic memory recall, decay, or reinforcement.
4. Upload-time analysis is asynchronous. The main Agent does not perform arbitrary image Q&A; it reads current facts with `get_product_visual_facts`.
5. A visual model submits facts through one forced tool call. It cannot choose the target package, SKU, image, row, status, or execution epoch.
6. A current input fingerprint with no successful snapshot yields `ANALYSIS_PENDING` or `UNAVAILABLE`. Historical snapshots are retained for audit but never returned as current facts.
7. Every actual provider attempt consumes the work item's persistent budget. Transport retry and structure-repair calls share a maximum of three actual provider attempts; at most two structure rounds are allowed.
8. Marketing copy is natural-language output produced by the main Agent and remains in the conversation. A redraw prompt is YAML produced by the main Agent and enters image generation only after user confirmation.

## 2. Context boundary

| Concern | Owner | Notes |
|---|---|---|
| Resolve image references and preprocess model inputs | `module/vision` using `infra/storage` and `infra/image` | Decode once, resize once, encode once |
| OpenAI-compatible multimodal provider call | `infra/ai` | Existing provider, role routing, quota and retry primitives |
| Package-level trigger and SKU-level distribution | `module/vision` using `infra/mq` | Internal background job, not a user-visible `process_task` |
| SKU/image visual snapshots and work-item state | `module/vision` | MySQL fact source |
| Redisson ownership lock | `module/vision` using `infra/cache` | Shared by asynchronous consumption and synchronous compensation |
| Product-copy source data | `module/file` / commerce data path | `asset_copy` remains uploaded/user-provided source material |
| Marketing copy | Main Agent | Natural text; does not overwrite `asset_copy` |
| Redraw Prompt Draft | Main Agent | YAML; confirmed before `submit_imagegen_plan` |
| Image generation | `module/imagegen` + `module/task` | Existing confirmed asynchronous execution path |
| Image Result criteria | `module/rubrics` | Rubrics calls its own AI role and does not depend on this module |

The old upload-time `ProductCopyExtractor -> asset_copy` path and the main-Agent `agent(type=vision)` path are removed. `agent(type=explore)` remains an Agent concern and is unrelated to this context.

## 3. Domain model

### 3.1 Product Visual Facts

The public fact contract has a stable common section and bounded category-specific attributes:

```json
{
  "common": {
    "categoryAppearance": "handbag",
    "dominantColors": ["black"],
    "visibleMaterials": ["leather-like textured surface"],
    "shapes": ["rectangular silhouette"],
    "visibleComponents": ["two handles", "metal clasp"],
    "patterns": [],
    "visibleText": [],
    "background": "white",
    "viewTypes": ["front three-quarter"]
  },
  "attributes": [
    {"name": "closure", "value": "top zipper"}
  ],
  "limitations": ["actual material cannot be confirmed from the submitted views"],
  "conflicts": []
}
```

Rules:

- `common` is versioned and strongly typed.
- `attributes` is a flat list of bounded `name/value` observations. Arbitrary nested JSON is forbidden.
- Missing or unseen properties are omitted; the model must not fill a comprehensive product catalog by guessing.
- `limitations` describes evidence boundaries, including single-view coverage.
- `conflicts` preserves incompatible observations rather than silently selecting one.
- Provider, model, prompt, usage, selected images, preprocessing and attempt details are operational metadata, not fields in Product Visual Facts returned to the Agent.

### 3.2 SKU Visual Snapshot

A SKU Visual Snapshot is identified by:

```text
(packageId, skuId, visualInputFingerprint)
```

It contains one Product Visual Facts document derived from at most two selected SKU images. With one available image, that image is analyzed once and the snapshot carries a single-view limitation. With no images, no provider call is made and the work item becomes unavailable with `NO_IMAGE`.

### 3.3 Image Visual Snapshot

An Image Visual Snapshot is identified by:

```text
(packageId, skuId, imageId, imageFingerprint, analysisContractVersion)
```

It records target-image facts for exact composition, view, visible text, background and other preservation-relevant observations. It does not contain generation or preservation instructions. It is persisted whenever focused compensation succeeds. Its observations do not flow back into the SKU snapshot as universal SKU facts.

### 3.4 Visual Input Fingerprint

For an SKU snapshot:

```text
SHA-256(
  sorted(all current asset-image content hashes)
  + visual facts schema version
  + vision prompt/tool schema version
  + preprocessing profile version
)
```

For an image snapshot, replace the image-set component with the target image content hash. Provider model identity is stored in operational metadata; changes to the configured analysis contract that can alter semantics must advance one of the explicit contract versions above.

When the current fingerprint changes, the previous snapshot remains immutable history, a new work item is created, and the new analysis is automatically distributed.

## 4. Persistence model

The target schema uses separate execution and fact records:

| Table | Identity and key fields | Purpose |
|---|---|---|
| `vision_analysis_job` | `id`, `package_id`, aggregate status/counts | Internal package-level batch created after extraction |
| `vision_analysis_item` | `id`, `job_id`, `package_id`, `sku_id`, nullable `image_id`, `scope`, `input_fingerprint`, `status`, `run_epoch`, `heartbeat_at`, `provider_attempt_count`, `structure_round_count`, final failure | Recoverable SKU or image analysis work item |
| `asset_visual_analysis` | `id`, `package_id`, `sku_id`, `input_fingerprint`, `facts_json`, operational metadata, `created_at` | Immutable SKU Visual Snapshot |
| `asset_image_visual_analysis` | `id`, `package_id`, `sku_id`, `image_id`, `input_fingerprint`, `facts_json`, operational metadata, `created_at` | Immutable Image Visual Snapshot |

Required uniqueness:

```text
UNIQUE(package_id, sku_id, scope, image_id, input_fingerprint)
```

`image_id` is null for SKU scope. Implementations may use two partial identities or separate work-item tables if MySQL null uniqueness would weaken this invariant; the application and database must still enforce one current work item per identity.

`asset_copy` is not written by vision. It remains the source product-copy record imported from user documents or other trusted product-data inputs.

## 5. Asynchronous analysis flow

After a package reaches its extraction terminal state:

```text
file publishes PACKAGE_VISUAL_ANALYSIS_REQUESTED {packageId}
  -> VisionAnalysisJobCoordinator creates one job
  -> reads asset_image grouped by (packageId, skuId)
  -> freezes each current input fingerprint
  -> creates one SKU Visual Analysis Work Item per SKU
  -> publishes VISION_ANALYZE_ITEM {itemId}
```

The package message is only the entry point. Provider calls and recovery are SKU work-item scoped so one SKU cannot block or replay the entire package.

### 5.1 Deterministic image sampling

```text
sampleSize = min(2, availableImageCount)
seed = SHA-256(packageId + skuId + visualInputFingerprint)
selected = deterministicShuffle(images sorted by stable image identity, seed).take(sampleSize)
```

The same image set always selects the same images across MQ redelivery, recovery and lookup compensation. A changed set changes the fingerprint and may select a different sample. The same image is never duplicated to fill two slots. Selected images are sent in one multimodal request to produce one SKU-level result.

### 5.2 Work-item state

```text
PENDING -> RUNNING -> SUCCESS
                   -> FAILED
                   -> EXPIRED
```

- `SUCCESS` belongs to one immutable input fingerprint and is never overwritten.
- `FAILED` means the current compensation/retry window is exhausted or terminal.
- `EXPIRED` means a stale running owner was detected; it may be reclaimed under a new execution epoch if provider budget remains.
- A new image-set fingerprint creates a new work item rather than reopening an old success.

The package job derives aggregate counts from its items. It is operationally queryable but is not shown as a user-created image-processing task.

## 6. Ownership, recovery and cost ceiling

The async consumer and the Agent lookup compensation path use the same execution owner:

```text
lock:vision:{packageId}:{skuId}:{scope}:{imageId-or-sku}:{inputFingerprint}
```

Execution protocol:

1. Acquire a Redisson watchdog `RLock`.
2. Atomically claim the MySQL work item and increment `run_epoch`.
3. Mark `RUNNING` and refresh `heartbeat_at` while work is active.
4. Before every actual provider HTTP attempt, atomically reserve one persistent provider attempt for the current epoch.
5. Commit a snapshot only when the owner thread still holds the lock and the item remains `RUNNING` at the same epoch.
6. Mark `SUCCESS` in the same transaction that publishes the fact row as current for its fingerprint.

The recovery scanner reads stale `RUNNING + heartbeat_at`, marks/requeues eligible items and never reads lock state or calls `forceUnlock`. A stale owner may finish its HTTP request, but its epoch-mismatched result cannot commit.

### 6.1 Shared provider-attempt budget

Each work item has:

```text
max actual provider attempts = 3
max structure rounds = 2
```

Transport retry and structure repair share the three-attempt budget. The attempt is reserved before the provider boundary; if a crash makes delivery uncertain, it remains consumed. MQ redelivery and lookup compensation do not reset the counter.

The current global `ModelRetryRunner` default must not multiply this work-item budget. `infra/ai` must accept a request-scoped attempt budget/counter seam, or expose each attempt through a callback that vision persists before outbound admission. Vision does not wrap `VisionModelClient` in an independent retry loop that can produce `structureRounds * transportRetries` calls.

## 7. Image selection and preprocessing

The versioned `vision-global-v1` profile is:

```yaml
max-long-edge: 1280
max-output-pixels: 1600000
output-format: jpeg
jpeg-quality: 0.85
transparent-background: WHITE
max-source-bytes: 10MiB
max-decoded-pixels: 40000000
auto-orient: true
upscale: false
```

Pipeline:

```text
reopenable source
  -> bounded probe and format/dimension validation
  -> reject decompression-bomb dimensions before decode
  -> EXIF orientation
  -> one resize satisfying both edge and pixel ceilings
  -> flatten alpha on white
  -> one JPEG encode
  -> VisionRequest image content
```

Images are processed separately and submitted as separate multimodal parts; they are not stitched into a contact sheet. If one sampled image fails preprocessing, the remaining valid image can still produce an SKU snapshot with a limitation. If all selected images fail, the work item fails without a provider call.

This pipeline must use `infra/image`'s `ReopenableImageSource` and global Pixel Budget. Vision does not introduce a second codec or unbounded `byte[]` path.

## 8. Forced visual-facts tool call

Vision uses the existing provider path:

```text
module/vision
  -> VisionModelClient
  -> DefaultVisionModelClient
  -> ChatModelClient
  -> configured OpenAI-compatible /v1/chat/completions endpoint
```

No VLLM-specific provider type or deployment management is introduced.

Every analysis request contains exactly one provider-visible tool:

```text
submit_product_visual_facts
```

The request uses `tool_choice=REQUIRED` and, where the OpenAI-compatible protocol permits, names that exact function. The tool's JSON Schema is the Product Visual Facts contract, with `additionalProperties=false`, bounded list sizes and bounded scalar lengths.

The visual model does not execute a PixFlow `ToolHandler` and does not write the database. Its tool call is an output envelope. Vision performs this sequence:

```text
VisionModelClient.call
  -> require exactly one submit_product_visual_facts call
  -> parse arguments as JSON
  -> validate schema and observation-only constraints
  -> if invalid and structure round remains, call again with the validation error
  -> fenced application commit to the work item and snapshot table
```

The tool arguments must not contain `packageId`, `skuId`, `imageId`, `jobId`, `itemId`, fingerprints, status, epoch, attempt counters or persistence fields. Those values come only from the server-owned work item.

Free text or a missing/wrong/multiple tool call is not a degraded success. It consumes its provider attempt and either enters the second structure round or fails. Raw provider text may be retained in restricted operational diagnostics but never becomes Product Visual Facts or Agent input.

## 9. Main-Agent lookup tool

The only product-vision tool exposed to the main Agent is:

```text
get_product_visual_facts(referenceKey)
```

The tool belongs to the Agent tool registry but its handler is supplied by `module/vision`. It is read-only from the user's domain perspective even though it may complete an internal missing analysis.

The handler resolves the backend-produced canonical key. SKU keys request a SKU snapshot; IMAGE keys request the concrete image snapshot and its SKU context. PACKAGE keys are accepted only when the tool can return a bounded package-level summary; it never guesses a SKU. Display paths and object keys are not identities.

### 9.1 SKU lookup

For a SKU reference:

- Return the successful current SKU Visual Snapshot when its fingerprint matches the current image set.
- If the item is `PENDING`, attempt to claim it for synchronous compensation.
- If the item is `RUNNING`, never start a duplicate provider call. Wait for a small configured interval, then return `ANALYSIS_PENDING` if it is still running.
- If the item is `FAILED` or `EXPIRED` and persistent provider budget remains, attempt one compensation execution under the same lock/fencing protocol.
- If no image exists, return `UNAVAILABLE(NO_IMAGE)`.

### 9.2 Target-image lookup

For an IMAGE reference:

- Resolve and validate the image's package/SKU lineage from the canonical key.
- Return both the current SKU facts, when available, and the current Image Visual Snapshot.
- If the target image has no current image snapshot, perform focused compensation using that one image and persist the result.
- Do not treat membership in the SKU sample as proof that image-specific facts were separately persisted; the SKU work item may explicitly materialize selected image snapshots to avoid a second provider call, but only if its output contract retains per-image observations. Otherwise focused analysis is required.

Tool result states are `AVAILABLE`, `ANALYSIS_PENDING`, and `UNAVAILABLE`. Historical facts are never embedded in pending or unavailable results.

### 9.3 Agent behavior

The main Agent must call the lookup tool before producing any output that depends on product appearance, including image-derived marketing copy, appearance descriptions, redraw prompts, preservation constraints or image comparisons.

If facts are unavailable, it may continue from user text, `asset_copy` and commerce data, but it must not invent visual claims. General `agent(type=vision)` and arbitrary `images + question` interfaces are not exposed.

## 10. Marketing copy and redraw prompts

### 10.1 Marketing copy

The main Agent combines user instructions, source product data, commerce data and current Product Visual Facts to produce natural-language marketing copy. The copy remains a conversation message by default and does not overwrite `asset_copy`.

Visual facts are evidence, not ready-made selling points. For example, `leather-like surface` cannot become `genuine leather`, and an observation from one view cannot become a claim about unseen structure.

### 10.2 Redraw Prompt Draft

For a concrete source image, the main Agent must request facts using its IMAGE `referenceKey` and emit a YAML Redraw Prompt Draft. The draft is safely parsed into a typed imagegen Proposal before publication. It separates preservation constraints from requested changes:

```yaml
version: 1
subject:
  description: black handbag
preserve:
  - black product color
  - rectangular silhouette
  - two handles
  - visible logo and text
change:
  background: light gray studio background
  lighting: soft side light
  shadow: natural contact shadow
negative:
  - do not add or remove product components
  - do not alter visible logo or text
output:
  aspect_ratio: "1:1"
  usage: ecommerce_main_image
```

YAML is an interchange format, not the image-provider contract. The server uses safe parsing, forbids custom tags/object construction, limits aliases, depth, scalar length and list size, validates a typed schema, and then projects it to the provider prompt.

The draft is shown for user confirmation. Only the existing confirmed `submit_imagegen_plan -> task -> ImageGenExecutor` path may start image generation.

## 11. Configuration

```yaml
pixflow:
  vision:
    image:
      profile-version: vision-global-v1
      max-long-edge: 1280
      max-output-pixels: 1600000
      output-format: jpeg
      jpeg-quality: 0.85
      transparent-background: WHITE
      max-source-bytes: 10MiB
      max-decoded-pixels: 40000000
      auto-orient: true
      upscale: false
    analysis:
      images-per-sku: 2
      max-provider-attempts: 3
      max-structure-rounds: 2
      running-wait: 2s
      heartbeat-interval: 15s
      stale-after: 2m
      schema-version: product-visual-facts-v1
      prompt-version: product-observation-v1
      topic: pixflow-vision
      package-tag: PACKAGE_VISUAL_ANALYSIS_REQUESTED
      item-tag: VISION_ANALYZE_ITEM
      consumer-group: pixflow-vision-analysis
      consumer-concurrency: 2
      intra-package-parallelism: 4
```

Model and provider remain under `pixflow.ai.roles.vision` and the existing OpenAI-compatible provider configuration. Vision does not contain provider or model literals.

## 12. Errors and observability

Domain errors must distinguish:

- invalid/missing image ownership;
- no decodable image;
- preprocessing safety rejection;
- invalid or missing facts tool call;
- facts schema/observation-constraint rejection;
- provider budget exhausted;
- stale ownership/fenced commit;
- persistence failure.

Provider network, rate-limit, context and provider failures remain normalized by `infra/ai`. Vision does not hide or translate away their recovery category.

Low-cardinality metrics include jobs/items by result, images received/selected/preprocessed, provider attempts, structure rounds, compensation outcomes, stale recovery and current-snapshot lookup outcomes. IDs, image bytes, complete facts, complete prompts and raw provider output do not become metric labels.

Operational metadata may retain selected image IDs, dimensions, encoded byte sizes, usage, provider/model identity and contract versions for audit. This metadata is not returned inside Product Visual Facts.

## 13. Module contracts

| Module | Contract |
|---|---|
| `infra/ai` | `VisionRequest` carries the forced facts tool schema and request-scoped attempt accounting; provider routing and outbound admission remain in AI |
| `infra/image` | bounded probe, global Pixel Budget, resize, alpha flatten and encode through the shared pipeline |
| `infra/storage` | resolve source image references; no visual snapshot bytes are stored in object storage |
| `infra/mq` | package and item distribution with at-least-once delivery; MySQL remains the work-item fact source |
| `infra/cache` | watchdog locks and lock guards; Redis is not the work-item fact source |
| `module/file` | publishes the package-ready visual-analysis request and supplies asset-image read models/content hashes; no compile dependency on vision is required |
| `harness/tools` | registers `get_product_visual_facts`; no generic vision subagent is registered |
| `agent` | must look up facts for appearance-dependent requests; owns marketing copy and YAML redraw-prompt reasoning |
| `module/imagegen` | accepts a confirmed typed projection of the YAML Redraw Prompt Draft; does not call vision |
| `module/memory` | does not ingest Product Visual Facts; only separately reviewed Agent insights may enter memory |
| `module/rubrics` | independent visual judge path through its own AI role; no dependency on vision |

## 14. Validation

Required tests include:

- deterministic sampling returns zero, one or two unique images and is stable across redelivery;
- fingerprint changes for image add/remove/replace or contract-version change;
- preprocessor enforces edge/pixel/source/decode ceilings, orientation, no upscale, alpha flatten and one decode/encode;
- forced tool schema rejects free text, wrong tool, multiple calls, extra fields, nested attributes and inferred prohibited claims;
- two structure rounds share one persistent three-attempt provider budget with transport retry;
- MQ redelivery and synchronous compensation cannot exceed three actual attempts;
- concurrent consumer and lookup compensation produce one owner and one current snapshot;
- stale owner result is rejected by `run_epoch` fencing;
- current fingerprint never receives historical facts;
- one-image SKU succeeds with a single-view limitation; zero-image SKU makes no provider call;
- focused target-image compensation persists and reuses an Image Visual Snapshot;
- main-Agent tool schema contains only `referenceKey` and no general vision-question tool exists;
- vision never writes `asset_copy`, Qdrant or memory;
- imagegen confirmation accepts only safe, typed YAML projection and never executes before confirmation;
- Testcontainers integration covers MySQL + Redis + RocketMQ + MinIO with a fake OpenAI-compatible vision endpoint.

## 15. Implementation transition

This is a development-stage one-way replacement:

1. Replace `CopyEnrichmentMessage/Consumer`, `ProductCopyExtractor`, `ProductCopyDraft` and `AssetCopyWriteMapper` with the package/job/item/snapshot flow.
2. Replace generic `VisionService.analyze(question)` and `VisionAssessment` consumption with internal snapshot analysis and the public lookup service.
3. Extend the existing `VisionModelClient` request to carry the forced tool schema/tool choice and request-scoped attempt accounting; do not add a second provider client.
4. Add the three MySQL tables and current-snapshot queries, plus Redisson lock and stale recovery wiring.
5. Register `get_product_visual_facts`; remove `agent(type=vision)` from Agent tool schemas and prompts.
6. Update imagegen proposal parsing so the Agent-facing redraw draft is safe YAML projected into the existing typed/confirmed execution path.

No compatibility flag, dual write to `asset_copy`, raw-text fallback, or generic vision-tool alias is retained.

## Revision Notes

2026-07-12 / Codex: Replaced the generic synchronous vision-subagent and upload-time copy-enrichment design with durable Product Visual Facts. Added package-triggered/SKU-work-item analysis, deterministic two-image sampling, SKU and target-image snapshots, fingerprint invalidation, RocketMQ/Redisson/MySQL recovery, shared three-attempt provider budgets, forced visual-facts tool calls, the Agent lookup/compensation contract, natural-language marketing-copy ownership and confirmed YAML redraw prompts.
