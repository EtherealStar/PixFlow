# module/file — Archive Materials and Asset Identity

## Responsibility

File owns archive upload sessions, package extraction, original material metadata, generated-image asset registration, canonical asset-reference resolution, Materials queries, and destructive asset cleanup. It does not own Agent turns, task scheduling, or output grouping by conversation/task.

## Asset model

An Asset Package represents one uploaded archive namespace. An Asset Image is a first-class processable image with stable `packageId` and `imageId`, `skuId`, current display name, storage reference, source type, and optional lineage.

`sourceType` is:

- `ORIGINAL`: extracted from the uploaded archive;
- `GENERATED`: promoted from a successful task result and assigned a new image ID.

Generated images inherit the source package/SKU but never enter PACKAGE or SKU expansion. Their lineage includes source task, result, and source image identities.

Canonical serialized keys, candidate views, expansion, and tombstone rules are defined in `../base/asset-references.md`. File is the backend producer and parser of these keys. Object-storage keys and display names are never accepted as business identity.

## Archive upload protocol

Browser upload accepts `.zip`, `.rar`, and `.7z` archives and uses:

```text
POST   /api/files/packages/init
GET    /api/files/packages/sessions/{uploadId}
PUT    /api/files/packages/sessions/{uploadId}/chunks/{index}
POST   /api/files/packages/sessions/{uploadId}/complete
DELETE /api/files/packages/sessions/{uploadId}
```

The whole-file multipart endpoint and optional standalone document field do not exist. Individual images and other non-archive files are unsupported. Supported image and metadata-document formats may occur inside the archive.

Limits are 2 GiB per archive and fixed 5 MiB chunks. `fileHash` is SHA-256 of the complete original archive bytes; `chunkHash` is SHA-256 of one chunk. The complete boundary recomputes the whole-file hash. Extension, magic bytes, and the selected extractor must agree.

Init serializes same-hash decisions and returns:

- `DEDUP` for an existing processable package;
- `RESUME` for the authoritative active session and uploaded indexes;
- `UPLOAD` for a new session.

Session state is Redis-backed and has a 12-hour sliding TTL refreshed by valid resume and successful chunk writes. Temporary chunks are removed on complete, cancel, or expiry. Production cleanup removes orphan temporary objects after their session has expired.

## Extraction

Complete creates an UPLOADED package and publishes extraction. Package state is:

```text
UPLOADED -> EXTRACTING -> READY | PARTIAL | FAILED
```

READY means every admitted original image completed. PARTIAL means at least one processable original image exists and at least one entry failed admission/extraction. FAILED contributes no mention candidates.

After READY/PARTIAL extraction, File publishes a package-ready domain event for initial Product Visual Facts analysis. Afterward, an Original Image add, delete, replacement, or content-hash change publishes an SKU-scoped visual-input-changed event. Rename, display ordering, and preview-URL changes do not. File owns the image/content facts but not the Vision work item; an application bridge connects these events without a File-to-Vision compile dependency.

Extraction applies format-specific path traversal, entry-count, decompressed-size, per-entry-size, compression-ratio, extension, encrypted-entry, and magic-byte controls. Password-protected archives are rejected rather than prompting for a password. Failure details are paginated and safe for user display.

## Cancellation

Upload-session DELETE cancels pre-complete upload and removes session/chunks.

`POST /api/files/packages/{packageId}/cancel-extraction` sets a durable cancellation marker checked between extraction/admission writes. The worker stops publishing new images and cleanup removes source archive bytes, extracted images, temporary objects, package browse data, and activity. If READY/PARTIAL/FAILED wins the terminal CAS first, cancellation returns conflict and does not undo success.

There is no retained Cancelled upload card or user-visible Paused package state.

## Queries

Package, SKU, package-image, error, and global original-image queries are 1-based paginated. The global image query supports server-side package/SKU/name filters and sort and never returns GENERATED rows.

Asset-reference browse/search queries provide Materials and Outputs hierarchies without requiring the Web client to enumerate packages. Exact excluded keys are removed before pagination.

## Generated-image registration

Task registers a successful output through a File API/port after its fenced success commit. Registration is idempotent by the successful task-result identity and creates one new generated image ID. File copies the TMP candidate to a stable `RESULTS` or `GENERATED` asset key, atomically records the asset row/lineage, and then deletes the candidate. A retry or event replay returns the existing image.

Task result rows may later be cleared without affecting the generated image. Output queries owned by the application/task read side use File lineage to group these images by conversation and task.

## Deletion

Deleting a package removes its source archive and every ORIGINAL image byte/processable row, so it disappears from Materials immediately. When generated images or historical references still require the namespace, File keeps only package ID/name and minimum original image ID/SKU/original-name tombstones. It does not retain a deletion time.

Deleting an original image removes its bytes and processable row and keeps only the minimum key/name tombstone needed by historical messages.

Deleting a generated image removes its bytes and processable row. It keeps only reference key/name tombstone data. It never deletes the source package, source image, sibling output, or task-independent asset.

## Tool resolution

Asset inspection accepts PACKAGE, SKU, or IMAGE reference keys and returns safe metadata/child keys. Deterministic processing accepts a list and expands to a deduplicated original/generated image union according to the shared contract. Tools requiring one image reject non-IMAGE keys.

## Invariants

1. PACKAGE and SKU expansion is stable over time because it contains originals only.
2. Generated outputs receive new image IDs and remain reusable independently of task history.
3. The backend, not the Web client, produces and resolves canonical keys.
4. Deletion removes bytes when the user requested deletion; tombstones contain names/identity only.
5. Upload sessions expire after 12 hours.
