# module/vision - Product Vision Understanding

> Authority: this document defines PixFlow's product-image understanding boundary. Domain terms are defined in `pixflow-module-vision/CONTEXT.md`; the current system-wide boundary decision is recorded in `docs/adr/0008-keep-only-current-product-visual-facts.md`, which supersedes ADR 0004.
>
> Scope: after asset extraction, analyze a bounded sample of each `(packageId, skuId)` through the existing OpenAI-compatible vision role, persist observation-only facts, and expose those facts to the main Agent through one lookup tool. Marketing copy and redraw prompts are not vision-model outputs.

## 1. Purpose and invariants

`module/vision` owns durable Product Visual Facts. It does not own a child Agent, general visual question answering, marketing copy, image-generation prompts, or image generation.

The following invariants are mandatory:

1. Product Visual Facts contain only what is directly observable in the submitted image views. Apparent material is recorded as appearance, not as an unverified physical property; unseen properties remain absent or become an Observation Limitation.
2. Facts are scoped by asset package and SKU. A `skuId` is not globally unique across packages.
3. Each SKU and target image has at most one current Product Visual Facts document in MySQL. Facts are replaced in place and no fact-revision history is retained. They are not Qdrant memories and do not participate in automatic memory recall, decay, or reinforcement.
4. Upload-time analysis is asynchronous. The main Agent does not perform arbitrary image Q&A; it reads current facts with `get_product_visual_facts`.
5. A visual model submits facts through one forced tool call. It cannot choose the target package, SKU, image, row, status, or execution epoch.
6. Only a change to the relevant image-content hashes automatically invalidates current facts. Model, prompt, preprocessing-profile, and fact-schema version changes do not automatically reanalyze existing assets.
7. A visual input with no current facts yields `ANALYSIS_PENDING` while its current work item is active and `UNAVAILABLE` after terminal failure. Existing facts remain available during manual reanalysis and after a failed manual reanalysis.
8. Every actual provider attempt consumes the current analysis generation's persistent budget. Transport retry and structure-repair calls share a maximum of three actual provider attempts; at most two structure rounds are allowed.
9. Marketing copy is natural-language output produced by the main Agent and remains in the conversation. A redraw prompt is YAML produced by the main Agent and enters image generation only after user confirmation.

## 2. Context boundary

| Concern | Owner | Notes |
|---|---|---|
| Resolve image references and preprocess model inputs | `module/vision` using `infra/storage` and `infra/image` | Decode once, resize once, encode once |
| OpenAI-compatible multimodal provider call | `infra/ai` | Existing provider, role routing, quota and retry primitives |
| Package-level trigger and SKU-level distribution | `module/vision` using `infra/mq` | Internal background job, not a user-visible `process_task` |
| Current SKU/image visual facts and work-item state | `module/vision` | MySQL fact source; no revision history |
| Administrator view, edit and reanalysis commands | `module/vision` exposed through the application Web adapter | Web edits replace current SKU facts; the Agent never sees author/source metadata |
| Redisson ownership lock | `module/vision` using `infra/cache` | Shared by asynchronous consumption, recovery, explicit reanalysis and focused target-image analysis |
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

### 3.2 SKU Visual Facts

A current SKU Visual Facts document is identified by:

```text
(packageId, skuId)
```

It contains the one current Product Visual Facts document derived from at most two deterministically selected Original Images in that SKU. The row records the current `visualInputFingerprint`, a monotonic optimistic-concurrency `version`, the last writer (`AI_GENERATED` or `ADMINISTRATOR_EDITED`) for Web display, and `updatedAt`. The writer is not part of Product Visual Facts and is not returned to the Agent.

With one available image, that image is analyzed once and the facts carry a single-view limitation. With no images, no provider call is made and the work item becomes unavailable with `NO_IMAGE`. An administrator may still save a structurally valid, completely empty Product Visual Facts document; that is a present current document and does not trigger analysis.

### 3.3 Image Visual Facts

A current Image Visual Facts document is identified by:

```text
(packageId, skuId, imageId)
```

It records target-image facts for exact composition, view, visible text, background and other preservation-relevant observations. It does not contain generation or preservation instructions. It is replaced in place whenever focused analysis succeeds. Its observations do not flow back into SKU Visual Facts as universal SKU facts, and it is not exposed by the Materials Product Visual Analysis form.

### 3.4 Visual Input Fingerprint

For SKU Visual Facts:

```text
SHA-256(sorted(all current Original Image content hashes for the SKU))
```

For Image Visual Facts, use the target image content hash. Display names, image order, preview URLs, model identity, prompt/tool version, fact-schema version, and preprocessing-profile version are not fingerprint inputs.

When the current image fingerprint changes, Vision clears the affected current facts, resets the existing work item under a new analysis generation, and automatically distributes analysis. When the fingerprint is unchanged, opening the UI, an Agent lookup, deployment, or analysis-contract change does not automatically reanalyze. A schema change that cannot read the current document requires an explicit data migration.

### 3.5 Administrator replacement

The configured administrator may replace current SKU Visual Facts through the Materials Product Visual Analysis form. The command sends the complete typed document and an expected `version`; a successful write replaces `facts_json`, increments `version`, records `ADMINISTRATOR_EDITED`, and retains no previous JSON. Structural bounds are enforced, while administrator text is accepted as authoritative correction without a second model review.

The administrator may also request reanalysis. Reanalysis keeps existing facts readable, makes the form read-only, resets the existing work item under a new `analysis_generation`, and grants that generation a fresh three-attempt provider budget. Success replaces current facts and records `AI_GENERATED`; terminal failure retains the previous facts. If no facts existed, terminal failure leaves the complete editable form empty.

## 4. Persistence model

The target schema uses separate execution and fact records:

| Table | Identity and key fields | Purpose |
|---|---|---|
| `vision_analysis_job` | `id`, `package_id`, aggregate status/counts, `updated_at` | One current internal package-level aggregate; reset rather than appended |
| `vision_analysis_item` | `id`, `package_id`, `sku_id`, nullable `image_id`, `scope`, `input_fingerprint`, `status`, `analysis_generation`, `run_epoch`, `heartbeat_at`, `provider_attempt_count`, `structure_round_count`, `last_request_id`, final failure | One current recoverable SKU or image analysis work item |
| `asset_visual_analysis` | `id`, `package_id`, `sku_id`, `input_fingerprint`, nullable `facts_json`, `version`, `last_writer`, bounded operational metadata, `updated_at` | One mutable current SKU Visual Facts document |
| `asset_image_visual_analysis` | `id`, `package_id`, `sku_id`, `image_id`, `input_fingerprint`, nullable `facts_json`, `version`, bounded operational metadata, `updated_at` | One mutable current Image Visual Facts document |

Required uniqueness:

```text
UNIQUE(package_id, sku_id, scope, image_id)
UNIQUE(package_id, sku_id)                       -- asset_visual_analysis
UNIQUE(package_id, sku_id, image_id)             -- asset_image_visual_analysis
```

`image_id` is null for SKU scope. Implementations may use separate work-item tables or a non-null target discriminator if MySQL null uniqueness would weaken the invariant. The application and database must enforce one current work item per SKU or target image.

Successful analysis, administrator replacement, reanalysis, and input invalidation update these rows in place. No fact-revision, completed-run, raw provider-output, or complete-prompt history table is created. Operational metadata is bounded and overwritten with the current generation.

`asset_copy` is not written by vision. It remains the source product-copy record imported from user documents or other trusted product-data inputs.

## 5. Asynchronous analysis flow

After a package reaches its extraction terminal state:

```text
file publishes PACKAGE_VISUAL_ANALYSIS_REQUESTED {packageId}
  -> VisionAnalysisJobCoordinator creates or resets the package's current job
  -> reads asset_image grouped by (packageId, skuId)
  -> freezes each current input fingerprint
  -> creates or resets one current SKU Visual Analysis Work Item per SKU
  -> publishes VISION_ANALYZE_ITEM {itemId}
```

The package message is only the entry point. Provider calls and recovery are SKU work-item scoped so one SKU cannot block or replay the entire package.

After initial extraction, File publishes `SKU_VISUAL_INPUT_CHANGED {packageId, skuId}` only when an Original Image is added, removed, replaced, or its content hash changes. Vision recomputes the content-only fingerprint. A changed fingerprint clears current SKU facts and resets the same work item under a new generation; an unchanged fingerprint is a no-op. Rename, ordering, preview-URL, model, prompt, schema, and preprocessing changes do not publish or cause automatic reanalysis.

### 5.1 Deterministic image sampling

```text
sampleSize = min(2, availableImageCount)
seed = SHA-256(packageId + skuId + visualInputFingerprint)
selected = deterministicShuffle(images sorted by stable image identity, seed).take(sampleSize)
```

The same image set always selects the same images across MQ redelivery, recovery and manual reanalysis. A changed set changes the fingerprint and may select a different sample. The same image is never duplicated to fill two slots. Selected images are sent in one multimodal request to produce one SKU-level result.

### 5.2 Work-item state

```text
PENDING -> RUNNING -> SUCCESS
                   -> FAILED
                   -> EXPIRED
```

- `SUCCESS` means the current generation wrote the current facts; a later administrator edit or analysis generation may replace them.
- `FAILED` means the current generation exhausted its provider budget or reached a terminal failure. Existing facts, if any, remain current.
- `EXPIRED` means a stale running owner was detected; it may be reclaimed under a new execution epoch if provider budget remains.
- A changed image-set fingerprint or manual reanalysis resets the same work item under a higher `analysis_generation`; neither operation appends a historical item.

The package job derives aggregate counts from its items. It is operationally queryable but is not shown as a user-created image-processing task.

## 6. Ownership, recovery and cost ceiling

The async consumer, stale recovery, explicit reanalysis, and focused target-image analysis use the same execution owner:

```text
lock:vision:{packageId}:{skuId}:{scope}:{imageId-or-sku}
```

Execution protocol:

1. Acquire a Redisson watchdog `RLock`.
2. Atomically claim the MySQL work item and increment `run_epoch`, retaining its current `analysis_generation`.
3. Mark `RUNNING` and refresh `heartbeat_at` while work is active.
4. Before every actual provider HTTP attempt, atomically reserve one persistent provider attempt for the current epoch.
5. Commit facts only when the owner thread still holds the lock and the item remains `RUNNING` at the same generation and epoch.
6. Replace the current fact row and mark the work item `SUCCESS` in one transaction. The replacement increments fact `version` and must compare the fact version captured when the generation began, so an administrator write from another tab wins over a stale model result.

The recovery scanner reads stale `RUNNING + heartbeat_at`, marks/requeues eligible items and never reads lock state or calls `forceUnlock`. A stale owner may finish its HTTP request, but its generation- or epoch-mismatched result cannot commit.

### 6.1 Shared provider-attempt budget

Each analysis generation has:

```text
max actual provider attempts = 3
max structure rounds = 2
```

Transport retry and structure repair share the three-attempt budget. The attempt is reserved before the provider boundary; if a crash makes delivery uncertain, it remains consumed. MQ redelivery and recovery do not reset the counter. An explicit administrator reanalysis or changed image fingerprint starts a higher generation and resets the same row's counter; opening the UI or an Agent lookup does not.

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

Images are processed separately and submitted as separate multimodal parts; they are not stitched into a contact sheet. If one sampled image fails preprocessing, the remaining valid image can still produce SKU Visual Facts with a limitation. If all selected images fail, the work item fails without a provider call.

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
  -> generation- and epoch-fenced replacement of the current fact row
```

The tool arguments must not contain `packageId`, `skuId`, `imageId`, `jobId`, `itemId`, fingerprints, status, epoch, attempt counters or persistence fields. Those values come only from the server-owned work item.

Free text or a missing/wrong/multiple tool call is not a degraded success. It consumes its provider attempt and either enters the second structure round or fails. Raw provider text may be retained in restricted operational diagnostics but never becomes Product Visual Facts or Agent input.

## 9. Main-Agent lookup tool

The only product-vision tool exposed to the main Agent is:

```text
get_product_visual_facts(referenceKey)
```

The tool belongs to the Agent tool registry but its handler is supplied by `module/vision`. It is read-only and returns the current facts without author/source metadata. It does not start a new SKU analysis generation.

The handler resolves the backend-produced canonical key. SKU keys request current SKU Visual Facts; IMAGE keys request current Image Visual Facts and their SKU context. PACKAGE keys are accepted only when the tool can return a bounded package-level summary; it never guesses a SKU. Display paths and object keys are not identities.

### 9.1 SKU lookup

For a SKU reference:

- Return current SKU Visual Facts whenever `facts_json` is present, including during or after a failed manual reanalysis.
- If facts are absent and the item is `PENDING`, `RUNNING`, or recoverable `EXPIRED`, return `ANALYSIS_PENDING`; the background owner/recovery path remains responsible for completion.
- If facts are absent and the item is terminal `FAILED`, return `UNAVAILABLE` without resetting provider budget.
- If no image exists, return `UNAVAILABLE(NO_IMAGE)`.

### 9.2 Target-image lookup

For an IMAGE reference:

- Resolve and validate the image's package/SKU lineage from the canonical key.
- Return both the current SKU facts, when available, and the current Image Visual Facts.
- If the target image has no current image facts, perform focused analysis using that one image and replace the current image fact row when it succeeds.
- Do not treat membership in the SKU sample as proof that image-specific facts were separately persisted; the SKU work item may explicitly materialize selected Image Visual Facts to avoid a second provider call, but only if its output contract retains per-image observations. Otherwise focused analysis is required.

Tool result states are `AVAILABLE`, `ANALYSIS_PENDING`, and `UNAVAILABLE`. `AVAILABLE` contains only current facts and does not reveal whether the last writer was the model or administrator.

### 9.3 Agent behavior

The main Agent must call the lookup tool before producing any output that depends on product appearance, including image-derived marketing copy, appearance descriptions, redraw prompts, preservation constraints or image comparisons.

If facts are unavailable, it may continue from user text, `asset_copy` and commerce data, but it must not invent visual claims. General `agent(type=vision)` and arbitrary `images + question` interfaces are not exposed.

### 9.4 Administrator application API

The application exposes current SKU facts to Materials through three authenticated operations:

- read the current facts and current analysis status for `(packageId, skuId)`;
- replace the complete facts document with an expected fact version;
- request a new analysis generation with an expected current generation and a per-click idempotency identity.

Read state keeps fact availability separate from analysis status. A response may therefore contain present facts with `analysisStatus=RUNNING` or `FAILED`. `lastWriter` and `updatedAt` are administrator-facing presentation metadata only. A version conflict retains the caller's draft and never performs last-write-wins. Replacement is rejected while the caller's expected version is stale; model commit uses the same fact-version fence.

The Web polls the read operation only while the detail surface is open and the current analysis is active. It does not turn Vision work into Activity and does not cancel backend work when polling stops.

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
      sku-input-changed-tag: SKU_VISUAL_INPUT_CHANGED
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

Low-cardinality metrics include jobs/items by result, images received/selected/preprocessed, provider attempts, structure rounds, reanalysis outcomes, stale recovery and current-facts lookup outcomes. IDs, image bytes, complete facts, complete prompts and raw provider output do not become metric labels.

The current work row may retain a bounded set of selected image IDs, dimensions, encoded byte sizes, usage, provider/model identity and contract versions for diagnostics. It is overwritten by the next generation. Complete prompts, raw provider output, and previous fact documents are not retained.

## 13. Module contracts

| Module | Contract |
|---|---|
| `infra/ai` | `VisionRequest` carries the forced facts tool schema and request-scoped attempt accounting; provider routing and outbound admission remain in AI |
| `infra/image` | bounded probe, global Pixel Budget, resize, alpha flatten and encode through the shared pipeline |
| `infra/storage` | resolve source image references; Product Visual Facts are not stored in object storage |
| `infra/mq` | package and item distribution with at-least-once delivery; MySQL remains the work-item fact source |
| `infra/cache` | watchdog locks and lock guards; Redis is not the work-item fact source |
| `module/file` | publishes the package-ready visual-analysis request and supplies asset-image read models/content hashes; no compile dependency on vision is required |
| `harness/tools` | registers `get_product_visual_facts`; no generic vision subagent is registered |
| `agent` | must look up facts for appearance-dependent requests; owns marketing copy and YAML redraw-prompt reasoning |
| `pixflow-app` / Web adapter | resolves Materials image detail to SKU scope and exposes read, replace, and reanalyze commands without making Vision work an Activity record |
| `module/imagegen` | accepts a confirmed typed projection of the YAML Redraw Prompt Draft; does not call vision |
| `module/memory` | does not ingest Product Visual Facts; only separately reviewed Agent insights may enter memory |
| `module/rubrics` | independent visual judge path through its own AI role; no dependency on vision |

## 14. Validation

Required tests include:

- deterministic sampling returns zero, one or two unique images and is stable across redelivery;
- fingerprint changes for image add/remove/replace/content change, but not rename, ordering, preview URL, model, prompt, preprocessing or contract-version change;
- preprocessor enforces edge/pixel/source/decode ceilings, orientation, no upscale, alpha flatten and one decode/encode;
- forced tool schema rejects free text, wrong tool, multiple calls, extra fields, nested attributes and inferred prohibited claims;
- two structure rounds share one persistent three-attempt provider budget with transport retry;
- MQ redelivery, stale recovery and focused analysis cannot exceed the current generation's three actual attempts;
- concurrent consumer, recovery and manual reanalysis produce one owner for the current work row;
- stale owner result is rejected by `analysis_generation + run_epoch` fencing;
- administrator replacement and successful model analysis overwrite one current row and never create fact or completed-run history;
- model commit loses to a concurrent administrator fact-version change;
- manual reanalysis receives a fresh three-attempt budget, retains existing facts while running and on failure, and replaces them only on success;
- changed image content clears existing SKU facts before automatic analysis; unchanged image content does not reanalyze;
- a completely empty administrator facts document remains `AVAILABLE` and does not trigger analysis;
- one-image SKU succeeds with a single-view limitation; zero-image SKU makes no provider call;
- focused target-image analysis replaces and reuses current Image Visual Facts;
- administrator application API separates fact availability from analysis status, rejects stale expected versions and deduplicates a retried reanalysis request;
- main-Agent tool schema contains only `referenceKey` and no general vision-question tool exists;
- vision never writes `asset_copy`, Qdrant or memory;
- imagegen confirmation accepts only safe, typed YAML projection and never executes before confirmation;
- Testcontainers integration covers MySQL + Redis + RocketMQ + MinIO with a fake OpenAI-compatible vision endpoint.

## 15. Implementation transition

This is a development-stage one-way replacement:

1. Replace `CopyEnrichmentMessage/Consumer`, `ProductCopyExtractor`, `ProductCopyDraft` and `AssetCopyWriteMapper` with the package/current-work/current-facts flow.
2. Replace generic `VisionService.analyze(question)` and `VisionAssessment` consumption with internal current-facts analysis and the public lookup service.
3. Extend the existing `VisionModelClient` request to carry the forced tool schema/tool choice and request-scoped attempt accounting; do not add a second provider client.
4. Add mutable current job/work/fact tables, administrator read/replace/reanalyze APIs, Redisson lock and stale recovery wiring. Do not add revision or completed-run tables.
5. Register `get_product_visual_facts`; remove `agent(type=vision)` from Agent tool schemas and prompts.
6. Update imagegen proposal parsing so the Agent-facing redraw draft is safe YAML projected into the existing typed/confirmed execution path.

No compatibility flag, dual write to `asset_copy`, raw-text fallback, or generic vision-tool alias is retained.

## Revision Notes

2026-07-18 / Codex: Replaced immutable versioned visual snapshots with one mutable current SKU/image facts document and one current work item per scope. Added administrator editing and explicit reanalysis, content-only automatic invalidation, generation/epoch/version fencing, no fact/run history, Materials polling semantics, and the rule that Agent lookup returns current facts without writer metadata.

2026-07-12 / Codex: Replaced the generic synchronous vision-subagent and upload-time copy-enrichment design with durable Product Visual Facts. Added package-triggered/SKU-work-item analysis, deterministic two-image sampling, SKU and target-image snapshots, fingerprint invalidation, RocketMQ/Redisson/MySQL recovery, shared three-attempt provider budgets, forced visual-facts tool calls, the Agent lookup/compensation contract, natural-language marketing-copy ownership and confirmed YAML redraw prompts.
