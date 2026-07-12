# Image Processing

This context provides deterministic, local image transformation capabilities without storage, task, or DAG semantics.

## Language

**Local Pixel Operation**:
A deterministic transformation of one or more raster images that performs no network or storage I/O.
_Avoid_: DAG node, tool call

**Pixel Pipeline**:
A sequence of local pixel operations that decodes each source once and encodes the final result once.
_Avoid_: workflow, task pipeline

**Composed Pipeline**:
A pixel pipeline that transforms multiple members, combines them into one raster, and then applies final local operations.
_Avoid_: group task, group branch

**Pixel Budget**:
The configured amount of decoded raster work that may be admitted concurrently, independent of worker thread count.
_Avoid_: image count limit, thread limit
