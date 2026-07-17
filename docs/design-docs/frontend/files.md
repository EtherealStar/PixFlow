# Materials and Outputs Frontend Design

## Separation

Materials and Outputs are separate navigation entries, routes, stores, and query trees. They may share image-card, preview, selection, and download components, but they never load through one combined page or one untyped list.

## Materials

Materials shows uploaded archive packages and their original contents. Folder mode lazily loads package -> SKU -> original image. Flat mode uses a backend global original-image endpoint. Package lists, SKU lists, image lists, search, sort, and filters are backend paginated; the client never requests every package and then every image.

The page's only upload interaction is drag-and-drop. It accepts `.zip`, `.rar`, and `.7z` and routes every archive through the shared chunk upload runtime.

READY and PARTIAL packages are browsable. Uploading and Extracting packages are represented by Activity rather than incomplete gallery rows. FAILED packages expose their error through Activity and package diagnostics, not as fake images.

## Outputs

Outputs lazily loads conversation -> task -> successful generated image. It preserves task boundaries and provides direct links from Activity and current-runtime confirmed Proposal cards. It does not derive output groups by fetching every conversation and then all images.

A successful result has its own generated `imageId`, lineage, and IMAGE reference key. The output remains usable after its task execution record is cleared. Generated images are never included in PACKAGE/SKU expansion.

## Shared image actions

Preview and direct download use short-lived backend URLs and never store those URLs as identity. Batch download sends typed asset references to the backend bundle endpoint.

Rename changes the current display name but not the key or historical `displayPathSnapshot`. Search results and new messages use the current name.

Deleting a package, original image, or successful output uses one confirmation dialog. Batch deletion confirms once, reports per-item failures, and leaves failed items selected. A successful delete removes the item from browse and mention queries.

Package deletion removes the original archive and original image bytes. When generated outputs or message history still depend on the namespace, the backend retains only package/original-image identity and names; Materials still returns no package row. Generated outputs remain available.

Generated-image deletion removes its bytes and processable image row. Historical mention tokens retain only their key and display-name snapshot and render as deleted.

## Selection and paging

Selection is page-aware but keyed by stable resource identity. Bulk operations may span pages only when the backend accepts the complete selected identity set; the client never assumes unseen pages are loaded. Public page numbers are 1-based.

## Invariants

1. Materials queries only original assets; Outputs queries only generated assets.
2. Pre-signed URLs, filenames, and task result row IDs are not image identities.
3. Deleting task history does not delete successful output images.
4. Browser-side filtering never substitutes for backend filtering on paginated data.
