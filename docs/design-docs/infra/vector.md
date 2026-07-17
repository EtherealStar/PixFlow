# infra/vector — Read-only vector retrieval

## Responsibility

`infra/vector` wraps Qdrant dense-vector search behind provider-neutral records. In the current product runtime its only consumer is `module/memory`, and that consumer has read authority only.

This module does not own memory facts, extraction, consolidation, lifecycle decisions, ranking fusion, or prompt assembly. It does not provide an application-runtime upsert/delete path that Agent, Conversation, Task, Hooks, Rubrics, or Memory can call.

## Runtime API

```java
public interface VectorSearch {
    void verifyCollection(String collection, int dimension, Distance distance);

    List<ScoredPoint> search(
        String collection,
        float[] query,
        int topK,
        float threshold,
        VectorFilter filter
    );

    Optional<VectorPointView> get(String collection, String id);
}
```

- `verifyCollection` checks that the configured collection already exists with the required dimension and distance; it does not silently create or migrate a production collection.
- `search` returns results in descending similarity order after threshold and payload-filter application.
- `get` supports diagnostics and deterministic hydration of an already selected point.
- Qdrant client types never cross this boundary.

There is no runtime `upsert`, `delete`, `deleteByFilter`, rebuild queue, compensation writer, or mem0 ADD-only API.

## Retrieval semantics

`module/memory` produces the query embedding through `infra/ai`, calls this adapter for the ACTIVE `analysis_insight` projection, and fuses the returned ordering with MySQL FULLTEXT results. This adapter does not know the meaning of categories, SKUs, preferences, or insight lifecycle states; it only translates the supplied neutral filter.

The minimum filter DSL supports:

- `must`, `should`, and `mustNot` groups;
- exact match and match-any conditions;
- numeric ranges.

Sparse vectors, BM25, server-side RRF, entity extraction, and graph memory are outside this module. Keyword retrieval and RRF belong to `module/memory`.

## Data maintenance boundary

Qdrant is a read-optimized projection of an administrator-prepared dataset whose fact source remains MySQL. Importing, expiring, or reindexing that dataset is an operational maintenance procedure outside the online application runtime and outside the Agent tool surface.

Operational tooling may use Qdrant administration APIs under separate credentials, but those APIs are not exposed through this module's runtime Spring beans. A user message, Proposal decision, task completion, Hook, or Rubrics review can never trigger them.

## Reliability

- Collection absence, dimension mismatch, and invalid filters fail deterministically without retry.
- Transient gRPC/network failures receive bounded retry and timeout handling, then surface as a normalized dependency error.
- `module/memory` owns degradation: vector failure falls back to MySQL FULLTEXT or an empty insight section.
- Logs and diagnostics pass through the common sanitizer and never include vectors or unrestricted payload text.

## Configuration

```yaml
pixflow:
  vector:
    qdrant:
      host: localhost
      grpc-port: 6334
      api-key: ${QDRANT_API_KEY:}
      timeout: 5s
```

Collection name, dimension, distance, topK, threshold, and business filters are supplied by the read-side owner. Production startup validates the collection and fails the vector component closed without blocking non-vector application startup; Memory then degrades as documented.

## Contracts

| Consumer/provider | Contract |
|---|---|
| `module/memory` | Calls `verifyCollection`, `search`, and optional `get` for read-only ACTIVE-insight recall. |
| `infra/ai` | Does not depend on this module; Memory passes query embeddings into `VectorSearch`. |
| `common` | Normalizes and sanitizes dependency errors. |

`infra/vector` has no dependency on Memory, Agent, Task, Conversation, Hooks, Rubrics, File, or business entities.

## Verification

- architecture tests expose no runtime write method or Qdrant client type;
- collection verification detects absence, dimension mismatch, and distance mismatch;
- search tests cover topK, threshold, ordering, and filter translation against a real Qdrant container;
- transient failure is bounded and deterministic failure is not retried;
- memory recall remains side-effect free under every success and degradation path.

## Revision Notes

- 2026-07-17 / Codex: removed online upsert/delete, ADD-only ingestion, rebuild compensation, and Memory write ownership; retained only read-side vector retrieval. Dataset import/reindex is an operational procedure outside the product runtime.
