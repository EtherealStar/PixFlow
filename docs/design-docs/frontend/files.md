# Materials and Outputs Frontend Design

## Separation

Materials and Outputs are separate navigation entries, routes, stores, and query trees. They may share image-card, preview, selection, and download components, but they never load through one combined page or one untyped list.

## Materials

Materials shows uploaded archive packages and their original contents. Folder mode lazily loads package -> SKU -> original image. Flat mode uses a backend global original-image endpoint. Package lists, SKU lists, image lists, search, sort, and filters are backend paginated; the client never requests every package and then every image.

The page's only upload interaction is drag-and-drop. It accepts `.zip`, `.rar`, and `.7z` and routes every archive through the shared chunk upload runtime.

READY and PARTIAL packages are browsable. Uploading and Extracting packages are represented by Activity rather than incomplete gallery rows. FAILED packages expose their error through Activity and package diagnostics, not as fake images.

### Original-image detail

Selecting an Original Image navigates to `/materials/packages/:packageId/images/:imageId`. The detail surface preserves the originating gallery's query, filters, sort, paging and scroll state in page memory. Its left/main region shows the selected image at contain scale with clearly visible previous/next controls. Navigation follows the originating visible-result order and does not wrap; a direct URL falls back to the containing package's default image order. Returning restores the originating gallery when available.

Desktop uses a flexible image region plus an approximately 420 px Product Visual Analysis column. Tablet keeps a split layout with the analysis column near 40% width. Phone uses one detail column with the image above the analysis form and a sticky Save/Cancel action area. The global Activity panel remains a separate shell surface.

### Product Visual Analysis

The right column is titled `商品视觉分析` and says `这是同商品的综合视觉分析，基于该商品中最多 2 张素材图片生成。` It resolves the selected Original Image to its SKU scope and edits the one current SKU Visual Facts document. Selecting another image in the same SKU keeps the same document; selecting another SKU loads that SKU's document.

The form is a complete projection of Product Visual Facts and adds no prose summary:

- single-line inputs for category appearance and background;
- one multiline textarea per dominant colors, visible materials, shapes, visible components, patterns, visible text, view types, limitations and conflicts, with one list item per line;
- repeatable name/value rows for category-specific attributes.

Normalization trims values and removes empty or duplicate list lines. Empty scalar values and empty arrays are valid, and the administrator may save a completely empty facts document. The form is directly editable when analysis is not running. A dirty form enables Save and Cancel; Save replaces the complete document using its expected version, and Cancel restores the last loaded/saved value. A conflict keeps the local draft and asks the administrator to load the newer current value before retrying.

Unsaved edits disable Reanalyze. Moving to a different SKU, closing detail, or navigating elsewhere in the SPA opens a PixFlow `Save and leave / Discard and leave / Continue editing` dialog. Refresh, tab close, and browser close use the browser-native unsaved-change warning; drafts are not stored locally or on the backend.

### Analysis state

Initial extraction automatically starts SKU analysis. Merely opening detail never starts or restarts analysis. The detail runtime polls every two seconds only while it is open and the backend reports an active analysis; leaving detail stops polling but does not cancel backend work.

- With no facts and active analysis, the body shows a spinner and `正在分析商品图片`; Reanalyze is disabled.
- With existing facts and active reanalysis, the complete form remains visible but read-only and shows the same `正在分析商品图片`; Reanalyze is disabled.
- Successful analysis replaces the complete current document and makes it editable.
- Terminal initial failure exposes the complete empty editable form, a restrained failed status and Reanalyze.
- Terminal manual-reanalysis failure retains the previous editable facts.
- A transport failure does not change analysis state or clear facts; it shows `暂时无法获取分析状态` and Retry load.

Reanalyze is a one-click command with no confirmation and no cancel action. Each click receives one fresh backend analysis generation with at most three provider attempts. Duplicate delivery of the same click is idempotent. The current writer (`AI 生成` or `人工编辑`) and update time are administrator-facing metadata; they are not Product Visual Facts and are not shown to the Agent.

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
