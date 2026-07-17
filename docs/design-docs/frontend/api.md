# PixFlow Web API Contract

## Conventions

All paths use `/api`. JSON responses use the common envelope. Errors expose HTTP status, business `code`, safe `message`, optional `details`, and `traceId`. The frontend converts failures from `unknown` into a structured `ApiError`; it does not discard business fields.

All public list parameters are 1-based: `page >= 1`. Responses are:

```json
{ "records": [], "total": 0, "page": 1, "size": 20 }
```

An adapter may translate a legacy backend index internally, but frontend callers, URLs, stores, and components only use 1-based pages.

## Authentication

```text
POST /api/auth/login
POST /api/auth/refresh
POST /api/auth/logout
GET  /api/auth/me
```

`refresh` has no body and uses the HttpOnly refresh cookie. Login and refresh return an access token and user; the token is held in memory. There is no register route. `/api/auth/register` is unmapped and returns 404.

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

`assistant_message_completed` includes the durable `messageId`. `tool_status` contains only a safe product-language label and state; it never exposes raw arguments or results.

`proposal_ready` payload includes proposal summary data required by the card:

```json
{
  "proposalId": "proposal-1",
  "proposalType": "IMAGE_PROCESS",
  "title": "统一商品主图尺寸",
  "summary": "...",
  "referenceSummaries": ["summer.zip / SKU-001"],
  "createdAt": "..."
}
```

The server publishes this event only after complete proposal validation. Cards remain disabled until the turn-level `completed` event.

The stream is not command-replayable. On interruption the client reloads message history; it does not resend the POST automatically.

## Asset reference picker

The shared key and expansion rules are in `../base/asset-references.md`.

```text
GET /api/asset-references?source=MATERIALS&page=1&size=50
GET /api/asset-references?source=OUTPUTS&page=1&size=50
GET /api/asset-references?parentKey={referenceKey}&page=1&size=50
GET /api/asset-references?query={text}&page=1&size=50
```

`excludeReferenceKey` is a repeatable query parameter with at most 20 values. The backend excludes exact matches before pagination.

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

Extraction cancellation is idempotent while the package is not terminal. Success winning the terminal compare-and-set returns a conflict and leaves the package intact.

Material queries are:

```text
GET    /api/files/packages?page=1&size=20&query=&sort=
GET    /api/files/packages/{packageId}
GET    /api/files/packages/{packageId}/skus?page=1&size=50
GET    /api/files/packages/{packageId}/images?page=1&size=50
GET    /api/files/images?page=1&size=50&packageId=&skuId=&query=&sort=
GET    /api/files/packages/{packageId}/errors?page=1&size=20
PATCH  /api/files/packages/{packageId}
PATCH  /api/files/packages/{packageId}/images/{imageId}
DELETE /api/files/packages/{packageId}
DELETE /api/files/packages/{packageId}/images/{imageId}
```

`/api/files/images` is the required global original-image pager. The response never includes generated images.

## Outputs

Output browsing uses dedicated lazy queries:

```text
GET    /api/outputs/conversations?page=1&size=20&query=&sort=
GET    /api/outputs/conversations/{conversationId}/tasks?page=1&size=20
GET    /api/outputs/tasks/{taskId}/images?page=1&size=50
PATCH  /api/outputs/images/{imageId}
DELETE /api/outputs/images/{imageId}
```

Output images expose generated `imageId`, `referenceKey`, conversation/task lineage, source image lineage, display name, metadata, and a short-lived preview URL. They do not use `process_result.resultId` as the public image identity.

Deleting an output removes bytes and the processable generated-image row while retaining only key/name tombstone data for historical message rendering.

## Global Activity

```text
GET    /api/activities?page=1&size=50&status=&kind=
GET    /api/activities/{activityId}
POST   /api/activities/{activityId}/cancel
DELETE /api/activities/{activityId}
```

Activity is administrator scoped and spans conversations. DELETE applies the lifecycle rules in `tasks.md`: it may clear successful execution history without deleting outputs, or immediately clean failed task data.

Live events use the existing authenticated STOMP connection and one user destination:

```text
/user/queue/activity
```

Each frame includes `sequence`, `operation: UPSERT | REMOVE`, `activityId`, and the complete current Activity view for UPSERT. Reconnect reloads the REST snapshot before accepting frames newer than its cursor.

## Task commands

```text
POST /api/tasks/{taskId}/retry-failed
```

The client sends no idempotency header or failed-ID list. The backend derives `retry-failed:{sourceTaskId}`, snapshots authoritative failed work-unit identities, and returns the one direct derived task. Source task state and results remain immutable.

Task cancellation is invoked through its Activity action so the UI has one command surface. Internal task APIs may retain module-specific endpoints but are not separate frontend contracts.

## Downloads

Single-image downloads use short-lived URLs returned in image views. Typed batch selection uses:

```text
POST /api/downloads/bundle
```

Items contain a canonical IMAGE reference key and optional requested filename. The backend resolves source type, authorization, storage location, and current availability.

## Removed frontend contracts

The following are intentionally absent and must not be restored as compatibility paths:

- registration;
- archived-conversation queries;
- direct conversation attachment upload;
- top-level message `packageId` or legacy attachment types;
- confirmation challenge/token endpoints and random `Idempotency-Key` headers;
- whole-file multipart package upload;
- task-specific frontend WebSocket subscriptions and standalone task-detail APIs;
- Rubrics/evaluation-center APIs;
- Settings APIs;
- scalar score, promotion, and memory-writeback UI contracts.
