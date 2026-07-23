# PixFlow Web API Contract

## Scope and authority

This document is the target wire contract for the PixFlow Web refactor. It is
normative even while the current backend or Web implementation still exposes a
legacy route or field. Implementation progress and temporary mismatches belong
in the active ExecPlans, not in this document.

The terms Materials, Outputs, Product Visual Analysis, Proposal, Activity, and
Asset Reference have the meanings defined by the matching frontend documents
and context glossaries. A backend implementation type is not automatically a
wire DTO. App transport adapters project owner APIs into the shapes below and
must not expose persistence rows, object-storage locations, provider payloads,
permission proofs, or internal execution traces.

## Conventions

All HTTP paths use `/api`. The authenticated STOMP handshake is the sole
exception and uses `/ws/activity`. Unless an endpoint explicitly returns SSE
or a download, JSON uses one common envelope. Success is:

```json
{
  "success": true,
  "code": "OK",
  "data": {},
  "message": null,
  "details": null,
  "traceId": null
}
```

Failure keeps the HTTP status meaningful and returns:

```json
{
  "success": false,
  "code": "STABLE_BUSINESS_CODE",
  "data": null,
  "message": "safe user-readable message",
  "details": {},
  "traceId": "01J..."
}
```

Nullable envelope members may be omitted. The frontend unwraps `data` only
after checking `success` and converts failures from `unknown` into a structured
`ApiError`; it does not discard `code`, `details`, or `traceId`.

All public list parameters are 1-based: `page >= 1`. Responses are:

```json
{ "records": [], "total": 0, "page": 1, "size": 20 }
```

An adapter may translate an owner module's internal index, but frontend callers,
URLs, stores, and components only use 1-based pages. Invalid page, size, enum,
sort, or filter values are rejected; they are not silently ignored. Timestamps
are ISO-8601 UTC instants. IDs and `referenceKey` values are opaque to the Web
even when their JSON representation is numeric.

## Authentication

```text
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/logout
GET  /api/auth/me
```

`refresh` has no body and uses the HttpOnly refresh cookie. Login and refresh return an access token and user; the token is held in memory. There is no register route. `/api/auth/register` is unmapped and returns 404.

Login accepts:

```json
{ "username": "admin", "password": "..." }
```

Login and refresh return:

```json
{
  "accessToken": "...",
  "accessTokenExpiresAt": "...",
  "user": {
    "userId": 1,
    "username": "admin",
    "displayName": "Administrator"
  }
}
```

`GET /api/auth/me` returns the same `user` shape. Logout returns `data: null`
and clears the refresh cookie.

Login, refresh, `/me`, and every authenticated request verify that the account username equals `PIXFLOW_AUTH_ADMIN_USERNAME`. Other stored accounts receive an ordinary authentication failure.

## Conversations

```text
POST   /api/conversations
GET    /api/conversations?page=1&size=50
GET    /api/conversations/{conversationId}
DELETE /api/conversations/{conversationId}
GET    /api/conversations/{conversationId}/messages?page=1&size=50
POST   /api/conversations/{conversationId}/messages   (SSE response)
POST   /api/conversations/{conversationId}/turns/stop
```

DELETE is a real delete, not archive. It removes conversation messages, ephemeral Proposal runtime, and conversation activity. It is rejected while an Agent turn or task is running. Source Materials and successful generated images are independent and remain.

Conversation create accepts `{ "title": "optional title" }`. List, create,
and detail use this public view and never expose an `archived` flag:

```json
{
  "conversationId": "conversation-1",
  "title": "Summer campaign",
  "createdAt": "...",
  "updatedAt": "..."
}
```

The list contains active conversations only. There is no `archived` or
`includeArchived` query parameter. DELETE returns `data: null` after the real
delete has committed.

Message submission is JSON:

```json
{
  "prompt": "处理 @summer.zip，但参考 @front-v2.png",
  "references": [
    {
      "referenceKey": "package:123",
      "displayPathSnapshot": "summer.zip"
    },
    {
      "referenceKey": "package:123/image:991",
      "displayPathSnapshot": "summer.zip / SKU-001 / front-v2.png"
    }
  ]
}
```

The array order matches mention-token order. `prompt` may be blank only when at least one reference exists. Legacy top-level `packageId`, `attachments`, `UPLOAD_IMAGE`, and `PACKAGE_REFERENCE` inputs are not accepted.

Message history is newest-page-first. Within one returned page, records are in
ascending `seq` so they can be prepended without reordering. Its safe public
view is:

```json
{
  "messageId": "message-1",
  "seq": 42,
  "role": "USER",
  "content": "处理这张图",
  "references": [
    {
      "referenceKey": "package:123/image:991",
      "displayPathSnapshot": "summer.zip / SKU-001 / front-v2.png"
    }
  ],
  "createdAt": "..."
}
```

Public history roles are only `USER | ASSISTANT`. Tool calls, tool results,
compaction markers, internal task bindings, and trace metadata are not history
wire fields. `references` is always an array and is non-empty only for USER
messages.

### Agent SSE events

The response stream uses named events:

```text
assistant_delta
assistant_message_completed
tool_status
transition
proposal_ready
completed
error
```

Every SSE `data` payload is JSON. `assistant_delta` contains `text`.
`assistant_message_completed` contains `messageId` and `finalText`.
`tool_status` contains only `label` and
`state: QUEUED | RUNNING | SUCCEEDED | FAILED`. `transition` contains a safe
product-language `label` and optional `state`; it never exposes a retry stack or
provider error. `completed` contains the durable `messageId` when one was
created and `stopped: boolean`. `error` contains `code`, safe `message`, and an
optional `traceId`.

SSE payloads never contain raw tool names, arguments, results, canonical keys,
object locations, provider prompts, permission facts, stack traces, or internal
trace spans.

`proposal_ready` payload includes proposal summary data required by the card:

```json
{
  "proposalId": "proposal-1",
  "conversationId": "conversation-1",
  "proposalType": "IMAGE_PROCESS",
  "title": "统一商品主图尺寸",
  "summary": "...",
  "referenceSummaries": ["summer.zip / SKU-001"],
  "createdAt": "..."
}
```

The server publishes this event only after complete proposal validation. Cards remain disabled until the turn-level `completed` event.

`proposalType` is `IMAGE_PROCESS | IMAGEGEN`. The stream is not
command-replayable. On interruption the client reloads message history; it does
not resend the POST automatically. A heartbeat is an SSE comment, not a product
event.

## Asset reference picker

The shared key and expansion rules are in `../base/asset-references.md`.

```text
GET /api/asset-references?source=MATERIALS&page=1&size=50
GET /api/asset-references?source=OUTPUTS&page=1&size=50
GET /api/asset-references?parentKey={referenceKey}&page=1&size=50
GET /api/asset-references?query={text}&page=1&size=50
```

`excludeReferenceKey` is a repeatable query parameter with at most 20 values. The backend excludes exact matches before pagination.

Exactly one of `source`, `parentKey`, or non-blank `query` is used for a browse
request. `source` is `MATERIALS | OUTPUTS`. Supplying conflicting selectors is
rejected. A child request uses the opaque `parentKey` returned by the previous
page; the client never parses it.

Candidate shape:

```json
{
  "referenceKey": "package:123/image:789",
  "kind": "IMAGE",
  "sourceType": "ORIGINAL",
  "displayPath": "summer.zip / SKU-001 / front.jpg",
  "hasChildren": false
}
```

Empty queries browse lazily. Text queries search both sources and add `sourceGroup: MATERIALS | OUTPUTS` to each result. Only processable resources are returned.

`sourceType` is present only for IMAGE candidates and is
`ORIGINAL | GENERATED`. `sourceGroup` is present on cross-source search results.
PACKAGE and SKU candidates may have children; generated IMAGE candidates never
do.

## Proposals

```text
POST /api/conversations/{conversationId}/proposals/{proposalId}/confirm
POST /api/conversations/{conversationId}/proposals/{proposalId}/reject
```

Confirm and reject have empty bodies. No `Idempotency-Key`, challenge ID, answer, token, expected count, or client-generated random key is accepted. `proposalId` is the business idempotency identity.

Confirm returns:

```json
{ "proposalId": "proposal-1", "taskId": "task-1", "status": "CONFIRMED" }
```

Repeated confirm returns the task already bound to the same `proposalId`. Reject and expiration delete the ephemeral Proposal without writing a message, reason, context event, or audit row. Repeated reject is successful. There is no pending-Proposal query or reload recovery contract.

## Uploads and packages

Browser upload uses only:

```text
POST   /api/files/packages/init
GET    /api/files/packages/sessions/{uploadId}
PUT    /api/files/packages/sessions/{uploadId}/chunks/{index}
POST   /api/files/packages/sessions/{uploadId}/complete
DELETE /api/files/packages/sessions/{uploadId}
POST   /api/files/packages/{packageId}/cancel-extraction
```

There is no multipart `POST /api/files/packages` endpoint. Init accepts an allowed archive filename (`.zip/.rar/.7z`), size, standard whole-file SHA-256 `fileHash`, and fixed 5 MiB `chunkSize`; it returns `UPLOAD`, `RESUME`, or `DEDUP` as described in `upload.md`.

Init request and response are:

```json
{
  "filename": "summer.zip",
  "size": 7340032,
  "fileHash": "64 lowercase hex characters",
  "chunkSize": 5242880
}
```

```json
{
  "mode": "UPLOAD",
  "uploadId": "upload-1",
  "packageId": null,
  "status": null,
  "chunkSize": 5242880,
  "expectedChunks": 2,
  "uploadedChunks": []
}
```

`RESUME` returns `uploadId`, the authoritative chunk size/count, and sorted
`uploadedChunks`. `DEDUP` returns `packageId` and
`status: READY | PARTIAL`; upload fields are null or zero. Session GET
adds sorted `failedChunks` and
`status: UPLOADING | COMPLETING | COMPLETED | CANCELLED`. Chunk PUT requires
`Content-Type: application/octet-stream`, `X-Chunk-Hash`, and `X-Chunk-Size`;
its response contains `uploadId`, `index`, `status`, and `uploadedChunks`.
Complete returns `packageId` and package `status`. Session DELETE returns
`uploadId` and `status: CANCELLED`.

Extraction cancellation is idempotent while the package is not terminal. Success winning the terminal compare-and-set returns a conflict and leaves the package intact.

Material queries are:

```text
GET    /api/files/packages?page=1&size=20&query=&sort=
GET    /api/files/packages/{packageId}
GET    /api/files/packages/{packageId}/skus?page=1&size=50
GET    /api/files/packages/{packageId}/images?page=1&size=50&skuId=&query=&sort=
GET    /api/files/packages/{packageId}/images/{imageId}
GET    /api/files/images?page=1&size=50&packageId=&skuId=&query=&sort=
GET    /api/files/packages/{packageId}/errors?page=1&size=20
PATCH  /api/files/packages/{packageId}
PATCH  /api/files/packages/{packageId}/images/{imageId}
DELETE /api/files/packages/{packageId}
DELETE /api/files/packages/{packageId}/images/{imageId}
```

`/api/files/images` is the required global original-image pager. The response never includes generated images.

Package `sort` is
`UPDATED_DESC | UPDATED_ASC | NAME_ASC | NAME_DESC`. Original-image `sort` is
`CREATED_DESC | CREATED_ASC | NAME_ASC | NAME_DESC`. The default is the first
value in each set. `query` is a trimmed display-name/package-name search.
`packageId` and `skuId` are exact filters. The package-scoped image list uses
the same image sort and optional `skuId/query` filters as the global image list;
it never returns GENERATED images.

Package list records contain `packageId`, `displayName`,
`status: READY | PARTIAL`, original-image/SKU counts, and timestamps. SKU records
contain `packageId`, `skuId`, and original-image count. Package detail may also
include safe extraction diagnostics, but never storage keys or archive paths.

Original-image views expose `packageId`, `skuId`, `imageId`, canonical IMAGE `referenceKey`, current display name, dimensions/size metadata, and a short-lived preview URL. This lets the image-detail route resolve its SKU-scoped Product Visual Analysis without parsing a display path.

The reusable Original Image view is:

```json
{
  "imageId": "991",
  "packageId": 123,
  "skuId": "SKU-001",
  "referenceKey": "package:123/image:991",
  "sourceType": "ORIGINAL",
  "displayName": "front-v2.png",
  "width": 1600,
  "height": 1600,
  "sizeBytes": 345678,
  "contentType": "image/png",
  "previewUrl": "https://short-lived.example/...",
  "previewExpiresAt": "...",
  "createdAt": "..."
}
```

Image detail returns:

```json
{
  "image": {},
  "previousImageId": "990",
  "nextImageId": null
}
```

`image` is the complete Original Image view above. Previous/next follows the containing package's
default image order and exists for direct-URL fallback; when detail was opened
from a filtered gallery, the Web uses its preserved visible order instead.

Both PATCH endpoints accept only `{ "displayName": "..." }` and return the
updated view. Delete endpoints return `data: null`. The frontend implements a
batch delete as bounded calls to these single-resource endpoints and retains
each failed item; there is no separate batch-delete contract.

## Product Visual Analysis

Materials reads and replaces the one current SKU Visual Facts document through:

```text
GET  /api/vision/packages/{packageId}/skus/{skuId}/facts
PUT  /api/vision/packages/{packageId}/skus/{skuId}/facts
POST /api/vision/packages/{packageId}/skus/{skuId}/reanalyze
```

`skuId` is URL encoded and remains scoped by `packageId`. GET returns fact availability and the current analysis status independently:

```json
{
  "packageId": 123,
  "skuId": "SKU-001",
  "analysisStatus": "RUNNING",
  "analysisGeneration": 4,
  "facts": {
    "common": {
      "categoryAppearance": "handbag",
      "dominantColors": ["black"],
      "visibleMaterials": ["leather-like textured surface"],
      "shapes": ["rectangular silhouette"],
      "visibleComponents": ["two handles"],
      "patterns": [],
      "visibleText": [],
      "background": "white",
      "viewTypes": ["front three-quarter"]
    },
    "attributes": [{"name": "closure", "value": "top zipper"}],
    "limitations": [],
    "conflicts": []
  },
  "version": 7,
  "writer": "ADMINISTRATOR_EDITED",
  "updatedAt": "...",
  "failureCode": null
}
```

`analysisStatus` is the frontend projection
`PENDING | RUNNING | SUCCEEDED | FAILED`. Backend recovery-only states such as
expired work are projected to PENDING before crossing the wire. `facts` may be
present while status is RUNNING or FAILED because manual reanalysis retains the
current document until success. `facts: null` means there is no current
document; a deliberately saved all-empty Product Visual Facts object is
non-null and remains available. `failureCode` is a bounded safe code only for a
terminal FAILED analysis and is otherwise null. It is not a provider exception
or raw response.

`writer` is `AI_GENERATED | ADMINISTRATOR_EDITED | null` and exists only for
administrator presentation. The Agent lookup contract returns facts without
writer metadata.

PUT replaces the complete document and never appends a revision:

```json
{
  "expectedVersion": 7,
  "facts": {
    "common": {
      "categoryAppearance": "",
      "dominantColors": [],
      "visibleMaterials": [],
      "shapes": [],
      "visibleComponents": [],
      "patterns": [],
      "visibleText": [],
      "background": "",
      "viewTypes": []
    },
    "attributes": [],
    "limitations": [],
    "conflicts": []
  }
}
```

All-empty facts are valid. The backend trims and bounds scalar/list values and rejects unknown or nested fields. A stale `expectedVersion` returns `409 VISUAL_FACTS_VERSION_CONFLICT` without changing current facts. Replacement while analysis is active returns `409 VISUAL_ANALYSIS_ACTIVE`. Success returns the complete updated GET view with an incremented version and `writer=ADMINISTRATOR_EDITED`.

POST reanalysis accepts the current generation and one client-generated identity per deliberate click:

```json
{ "expectedGeneration": 4, "requestId": "01J..." }
```

Retrying the current row's same request ID returns the same analysis generation. A stale `expectedGeneration` returns `409 VISUAL_ANALYSIS_GENERATION_CONFLICT`, preventing a delayed retry from an older click from starting another run. A different request ID with the current generation starts a new generation only when none is active, resets the same work row's provider budget to three actual attempts, and retains existing facts until success. It creates no completed-run or fact-history row. The response is the complete current GET view. Reanalysis has no cancel endpoint and requires no confirmation.

The Web polls GET every two seconds only while detail is open and
`analysisStatus` is PENDING or RUNNING. It stops the previous poll and discards
a late response when `(packageId, skuId)` changes. Network failure is not an
analysis failure. Vision work is not returned by Global Activity.

## Outputs

Output browsing uses dedicated lazy queries:

```text
GET    /api/outputs/conversations?page=1&size=20&query=&sort=
GET    /api/outputs/conversations/{conversationId}/tasks?page=1&size=20
GET    /api/outputs/tasks/{taskId}/images?page=1&size=50
PATCH  /api/outputs/images/{imageId}
DELETE /api/outputs/images/{imageId}
```

Conversation-group sort is
`LATEST_OUTPUT_DESC | LATEST_OUTPUT_ASC | NAME_ASC | NAME_DESC`. Conversation
records contain `conversationId`, title snapshot, `generatedImageCount`, and
`latestGeneratedAt`. Task-group records contain `taskId`,
`taskType: IMAGE_PROCESS | IMAGEGEN`, generated-image count, and creation/finish
timestamps. These are Outputs read models, not Task execution aggregates.

Output images use:

```json
{
  "imageId": "1201",
  "referenceKey": "package:123/image:1201",
  "sourceType": "GENERATED",
  "displayName": "front-redraw.png",
  "packageId": 123,
  "skuId": "SKU-001",
  "conversationId": "conversation-1",
  "taskId": "task-1",
  "sourceImageId": "991",
  "width": 1600,
  "height": 1600,
  "sizeBytes": 456789,
  "contentType": "image/png",
  "previewUrl": "https://short-lived.example/...",
  "previewExpiresAt": "...",
  "createdAt": "..."
}
```

They never expose `process_result.resultId`, candidate keys, object-storage
locations, execution epochs, or provider payloads. PATCH accepts only
`{ "displayName": "..." }` and returns the updated image view.

Deleting an output removes bytes and the processable generated-image row while retaining only key/name tombstone data for historical message rendering.

## Global Activity

```text
GET    /api/activities?page=1&size=50&status=&kind=
GET    /api/activities/{activityId}
POST   /api/activities/{activityId}/cancel
POST   /api/activities/{activityId}/retry-failed
DELETE /api/activities/{activityId}
```

Activity is administrator scoped and spans conversations. DELETE applies the lifecycle rules in `tasks.md`: it may clear successful execution history without deleting outputs, or immediately clean failed task data.

The list response extends the normal page with the snapshot cursor:

```json
{
  "records": [],
  "total": 0,
  "page": 1,
  "size": 50,
  "cursor": 1842
}
```

An Activity view is:

```json
{
  "activityId": "opaque-activity-id",
  "kind": "PROCESS",
  "status": "PARTIALLY_SUCCEEDED",
  "progress": { "completed": 8, "total": 10, "failed": 2 },
  "conversationId": "conversation-1",
  "packageId": null,
  "taskId": "task-1",
  "createdAt": "...",
  "startedAt": "...",
  "finishedAt": "...",
  "allowedActions": {
    "cancel": false,
    "retryFailed": true,
    "clear": true
  },
  "sequence": 1842
}
```

`kind` is `UPLOAD | PROCESS | IMAGEGEN`. Wire status is `UPLOADING |
EXTRACTING | QUEUED | RUNNING | SUCCEEDED | PARTIALLY_SUCCEEDED | FAILED`.
Null links are omitted or returned as null. The
Web renders labels from these stable enums and never infers actions from status;
it uses `allowedActions`.

Live events use the existing authenticated STOMP connection and one user destination:

```text
STOMP handshake: /ws/activity
CONNECT header:  Authorization: Bearer <accessToken>
SUBSCRIBE:       /user/queue/activity
```

Each frame is:

```json
{
  "sequence": 1843,
  "operation": "UPSERT",
  "activityId": "opaque-activity-id",
  "view": {}
}
```

`view` is the complete current Activity view for UPSERT and null for REMOVE.
Reconnect reloads the REST snapshot before accepting frames whose `sequence`
is greater than its cursor. Duplicate or older frames are ignored.

Cancel and retry have empty bodies and are accepted only when their matching
`allowedActions` flag is true; owner state is rechecked server-side. Cancel
returns `data: null` and the eventual REMOVE/UPSERT arrives through Activity.
Retry returns the stable direct child identity:

```json
{
  "sourceActivityId": "activity-1",
  "activityId": "activity-2",
  "taskId": "task-2",
  "retryOfTaskId": "task-1"
}
```

Repeated retry resolves to the same direct child. DELETE returns `data: null`
after owner cleanup is accepted; its resulting REMOVE frame is still safe to
apply idempotently.

The client sends no idempotency header or failed-ID list. The backend derives
`retry-failed:{sourceTaskId}`, snapshots authoritative failed Work Unit
identities, and returns the one direct Derived Retry Task. Source Task state and
results remain immutable. Cancel, retry, and clear therefore share Activity as
the only frontend command surface; Task remains the owner of their business
rules behind that transport boundary.

## Downloads

Single-image downloads use short-lived URLs returned in image views. Typed batch selection uses:

```text
POST /api/downloads/bundle
```

Items contain a canonical IMAGE reference key and optional requested filename. The backend resolves source type, authorization, storage location, and current availability.

Request and response are:

```json
{
  "archiveName": "pixflow-images.zip",
  "items": [
    {
      "referenceKey": "package:123/image:1201",
      "filename": "front-redraw.png"
    }
  ]
}
```

```json
{
  "url": "https://short-lived.example/...",
  "expiresAt": "...",
  "contentType": "application/zip",
  "sizeBytes": 456789
}
```

Only IMAGE keys are accepted. Duplicate keys are deterministically deduplicated
before archive creation, preserving the first occurrence. Requested filenames
are sanitized and made unique by the backend. The Web never sends `type`,
`imageId`, `resultId`, object key, or bucket.

## Removed frontend contracts

The following are intentionally absent and must not be restored as compatibility paths:

- registration;
- archived-conversation queries;
- direct conversation attachment upload;
- top-level message `packageId` or legacy attachment types;
- confirmation challenge/token endpoints and random `Idempotency-Key` headers;
- whole-file multipart package upload;
- direct frontend `/api/tasks/**`, `/api/conversations/*/tasks`, task-result,
  task-download, task-cancel, and standalone task-detail APIs;
- task- or package-specific WebSocket/STOMP subscriptions;
- Rubrics/evaluation-center APIs;
- Settings APIs;
- scalar score, promotion, and memory-writeback UI contracts.
