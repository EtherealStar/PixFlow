# Archive Upload Frontend Design

## Entry points

Chat accepts `.zip`, `.rar`, and `.7z` archives through paperclip selection and drag-and-drop. Materials accepts the same formats through drag-and-drop only. Both use one upload runtime and permanently create Materials packages. Individual images and all other non-archive files are rejected before hashing with `只能上传压缩文件`.

The browser never calls a whole-file multipart endpoint and never uploads an optional standalone document. Supported metadata documents may exist inside the archive and are interpreted by File extraction.

## Protocol

Each archive follows:

```text
validate -> hash -> init -> upload missing chunks -> complete -> extract
```

`fileHash` is SHA-256 over the complete original archive byte stream. `chunkHash` is SHA-256 over one 5 MiB chunk. The two hashes are not interchangeable.

`init` returns exactly one mode:

- `UPLOAD`: a new session and empty uploaded set;
- `RESUME`: the authoritative active session and uploaded chunk indexes;
- `DEDUP`: an existing processable package.

RESUME and DEDUP are successful outcomes. The client uploads only the set difference for RESUME. DEDUP attaches the existing PACKAGE reference when the upload originated in Chat.

Only network failures and temporary 5xx responses receive bounded silent retries. Protocol and business 4xx responses do not retry automatically. Hash mismatch re-reads and retransmits the affected chunk only when the error is a chunk mismatch; whole-file mismatch restarts validation rather than inventing a new identity.

## Limits and concurrency

- maximum archive size: 2 GiB;
- chunk size: 5 MiB;
- active archive jobs per browser application: 2;
- active chunk requests per archive: 3;
- backend session TTL: 12 hours.

Files waiting for a client slot appear as Uploading at 0%. Hash work is included in Uploading progress. The client keeps only small session metadata and never stores archive bytes or chunks in IndexedDB.

## Resume and interruption

A browser refresh cannot recover the local File object. Activity therefore shows the interrupted upload and asks the user to select the same archive. The new hash locates the active backend session, then only missing chunks are sent. Selecting a different file cannot resume that session.

After complete, extraction is entirely backend-owned. Refresh restores Extracting from the global Activity snapshot without selecting the archive again.

There is no manual Pause button or Paused state. The only explicit active-job action is Cancel.

## Cancellation

Uploading cancellation aborts requests and asks the backend to delete session metadata and temporary chunks. Extracting cancellation records a backend cancellation marker, stops publication of new images, and removes the archive, extracted images, temporary objects, and package record.

Cancellation wins only before Succeeded. If success wins the terminal race, the cancel operation returns a conflict and the user may delete the package through Materials. Completed cancellation removes the Activity item.

## Chat integration

Each selected archive owns an independent job and package. On success, Chat adds the PACKAGE mention token to the current draft unless the exact key is already selected. Draft dispatch waits while any selected package is Uploading or Extracting and uses the fixed processing message from `product.md`.

## Invariants

1. Every browser upload uses init/chunks/complete.
2. The backend is authoritative for deduplication, active-session lookup, and terminal state.
3. Upload UI never reports extraction progress inside the Composer.
4. Session expiry is 12 hours, not the previous 24-hour default.
