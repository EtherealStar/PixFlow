# Frontend Transport Design

## HTTP client

The shared client attaches an in-memory access token and request trace, unwraps the common envelope, and normalizes backend pages without changing their 1-based page number. Error payloads are parsed as `unknown` and converted to `ApiError` with status, code, safe message, details, retry delay, and trace ID.

Only replay-safe GET requests retry once after a network failure or temporary 5xx. Authentication refresh is single-flight and replays at most once. Mutation retries belong to the owning runtime and require the stable business identity defined by that operation.

Product Visual Analysis uses ordinary authenticated HTTP. Its detail runtime polls current state every two seconds only while analysis is active and the detail surface remains open. It aborts the prior request when the selected SKU changes and discards a response whose SKU identity no longer matches. A transport failure is distinct from terminal model failure and never clears current facts.

Facts replacement is not automatically replayed after an ambiguous network failure; the runtime reloads current state and uses the expected fact version to reconcile. Reanalysis may retry with the same per-click request identity, which the backend stores only as the current work item's last request identity.

Chunk PUT requests bypass the ordinary HTTP concurrency pool and are bounded by the upload worker pool.

## Agent SSE

Agent turns use a fetch-based POST SSE client so headers and JSON body are available. The parser validates named event payloads and preserves unknown event data only for diagnostics. Components never parse the byte stream.

The turn runtime owns the AbortController and reducer. A stream ending before `completed` is interrupted, not successful. Reconciliation uses message history and message IDs rather than replaying the command.

## Activity STOMP

One authenticated STOMP connection subscribes to `/user/queue/activity`. Activity runtime is mounted at application scope. It reconnects with bounded backoff, reloads a REST snapshot, and applies only frames newer than the snapshot cursor.

Task- and package-specific topics are backend-internal compatibility details and are not used by the new frontend Activity model.

## Authentication transport

Refresh credentials exist only in the HttpOnly cookie. Access tokens are never persisted. Logging out closes SSE/WS transports and rejects pending authenticated HTTP work with a normalized authentication error.

## Observability

Trace IDs are logged and included in failed-operation UI as copyable error numbers. Successful requests and ordinary messages do not expose them in the interface.
