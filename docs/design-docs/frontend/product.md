# PixFlow Web Product Design

## Scope and authority

This document defines the user-visible PixFlow Web product. Module documents in this folder define its implementation boundaries, and `frontend/api.md` defines the HTTP and event contracts. Historical implementation audits and fixed-defect lists are not normative design.

PixFlow is an administrator-only operational application. There is no landing page, registration flow, Settings page, Rubrics page, evaluation center, or standalone task-detail page.

## Information architecture

Authenticated routes are:

```text
/                         -> /chat/new
/chat/new
/chat/:conversationId
/materials
/materials/packages/:packageId
/materials/packages/:packageId/images/:imageId
/outputs
/outputs/conversations/:conversationId/tasks/:taskId
```

`/login` is the only public page. Unknown or removed routes return the normal 404 page; legacy `/files`, `/tasks/:id`, `/rubrics`, and `/settings` routes are not retained as parallel mechanisms.

The left navigation contains New conversation, Conversations, Materials, Outputs, and the administrator menu with Logout. Materials and Outputs are separate pages because they use different backend resources and different hierarchies.

## Shell and responsive behavior

Desktop uses the conversation list, main content, and global Activity panel. The Activity panel is collapsed by default and never auto-expands when work starts.

On tablets both side panels are overlay drawers. On phones the top bar exposes conversation and activity icons, and both panels become full-screen or near-full-screen drawers. Drawers lock background scrolling and close via backdrop, browser back, or Escape. The Composer follows the visual viewport, soft keyboard, and safe-area insets. Grids, proposals, and queued-message actions become single-column without horizontal overflow.

There is no permanent trace ID widget. Failures may show a user-facing error number with a copy button.

## Conversations and streaming

Conversation history loads the newest page first and loads older pages when the user scrolls upward. History messages and live SSE items merge by backend `messageId`; the client never clears earlier assistant messages when a new turn starts.

The running turn is rendered as an ordered timeline of assistant text, user-readable tool status, transitions that matter to the user, and errors. Raw tool names, JSON arguments, reference keys, full tool results, and internal traces are never exposed.

If SSE ends without a terminal event, the client loads the latest history page and reconciles by message ID. If a complete message is unavailable, already received text remains visible and is marked interrupted. The prompt is never automatically resent after an SSE failure.

## Application-memory message queues

Each conversation owns one active Agent turn and a FIFO queue of at most five messages. Different conversations may run independently. Queue state lives in application memory, not in a page component, and therefore survives navigation among conversations, Materials, and Outputs in the same SPA instance.

Refresh, tab close, leaving the site, application termination, and another browser tab do not restore the queue. No queue data is written to localStorage, IndexedDB, or the backend.

Queued messages appear in a panel immediately above the Composer, not in the transcript. They show text, mention tokens, order, Edit, and Cancel. Editing is in place. If the Agent finishes while the head item is being edited, automatic dispatch waits for Save or Cancel. Dispatch is direct; there is no user-visible `sending` phase.

Normal completion and user Stop dispatch the next item. Network, SSE, or server errors pause that conversation's queue until the user selects Continue. A permanently unavailable reference pauses its item for editing. Pending Proposals pause automatic dispatch but the Composer may continue adding messages labelled `Waiting for proposal decisions`.

## Mentions

Mentions are atomic editor tokens bound to the shared Asset Reference Contract in `../base/asset-references.md`. A token cannot be partially edited and can be removed as one unit. Prompt text and ordered structured references are submitted separately.

The picker provides Materials and Outputs roots, lazy hierarchy browsing, global backend search, exact-key exclusion, a maximum of 20 selected references, and no historical-conversation pseudo references.

## Proposal interaction

The backend emits `proposal_ready` events. A turn may produce multiple Proposals, and each is confirmed or rejected independently. Cards may stream into the timeline, but their controls stay disabled until the turn's terminal `completed` event.

Before publication, the backend performs complete structural, permission, resource, readiness, count, and semantic-fact validation. Technical failures return to the Agent within the same turn for correction. A conflict between user intent and material facts is resolved by normal Agent conversation, not by silently changing the request.

Confirmation is one click. There is no ChallengeDialog, challenge answer, fixed confirmation phrase, batch-threshold prompt, or second user confirmation. The backend still performs hash verification, authorization, compare-and-set, and business idempotency. `proposalId` is the sole business idempotency identity.

Reject collects no reason. It stops that proposal flow, deletes the ephemeral Proposal, and removes the card. It does not inject a rejection event into Agent context, insert a visible rejection message, or require another prompt; the Agent's own proposal already exists in the conversation context. Expired proposals are deleted without context injection. Confirmed cards may remain only in the current SPA runtime; the durable task link lives in Activity.

When a background conversation receives proposals in the same SPA runtime, one clickable global toast identifies the conversation and the conversation list shows the in-memory pending count until all proposals are resolved. Proposals never appear in Activity and are not restored after reload.

## Materials and uploads

Materials accepts `.zip`, `.rar`, and `.7z` archives through drag-and-drop only. Chat accepts the same archive formats through drag-and-drop and a paperclip-backed multi-file picker. Individual images and other non-archive files are rejected before hashing with `只能上传压缩文件`.

Every accepted chat upload permanently enters Materials. Multiple archives create independent packages and Activity records. A successful chat upload automatically adds its PACKAGE reference to the current draft unless that exact key is already selected.

Uploads use only the chunked init/chunks/complete protocol. There is no multipart compatibility endpoint, direct conversation attachment upload, optional standalone document field, small-file fast path, or fallback protocol.

Limits are 2 GiB per archive and 5 MiB per chunk. The client processes at most two archives concurrently and uploads at most three chunks per archive concurrently. Whole-file Hash calculation is part of Uploading progress. Files waiting for a client slot remain in Uploading at 0%.

Upload Activity states are Uploading, Extracting, Succeeded, and Failed. The Composer never renders progress. A draft that references a non-terminal package cannot be sent and shows exactly `素材处理中，请点击右侧任务栏查看进度`.

There is no manual Pause control. A refresh or interruption requires the user to select the same archive again; the hash locates the session and only missing chunks are uploaded. Upload sessions expire after 12 hours. Once upload completes, extraction recovery is automatic from the backend Activity snapshot.

Uploading and Extracting can both be cancelled. Terminal cleanup removes the Activity item instead of retaining a Cancelled upload state. If success wins the race, cancellation fails and the user may delete the package normally.

## Materials and Outputs browsing

Materials and original-image flat views use backend pagination. A global image query is mandatory; the client must not enumerate every package to build a flat gallery. Search, sort, and filters are backend operations for paginated data.

Selecting an Original Image enters its dedicated detail route. The main area shows a large image with prominent previous/next controls, while a Materials-owned right column shows `商品视觉分析`. The helper text is `这是同商品的综合视觉分析，基于该商品中最多 2 张素材图片生成。` The facts belong to the image's `(packageId, skuId)`, so every image in the same SKU opens the same Product Visual Facts document.

Previous/next follows the filtered, sorted, visible result order from which detail was opened and stops at the ends. A directly opened detail URL instead uses the containing package's default image order. Back returns to the originating gallery state when it still exists, otherwise to the package detail. Product Visual Analysis is page content, not the global Activity panel.

The form shows every Product Visual Facts field and no additional summary. It is directly editable without an Edit mode. Save replaces the complete current document; Cancel restores the last loaded/saved document. Reanalysis is one click with no confirmation. Unsaved edits disable reanalysis and guard product changes, detail close, and in-app navigation with a PixFlow dialog; browser refresh/tab/window close uses the native unsaved-change warning.

Outputs uses lazy `conversation -> task -> generated image` navigation and preserves task boundaries. Successful generated images follow the shared Asset Reference Contract and remain independently reusable even if their task execution record is deleted.

Deleting a package, original image, or successful output uses one confirmation dialog; batch deletion confirms once and retains each failed deletion in the list. Deleting failed task data is immediate and needs no confirmation.

## Global Activity

Activity is global across all authenticated routes. It loads an administrator-scoped paginated REST snapshot and then consumes one administrator-scoped event stream. Reconnect always reloads a snapshot before applying new events.

Cards show work type, status, owning conversation when present, progress, and contextual actions. Card details expand within the panel. There is no standalone task-detail route. Explicit links navigate to the owning conversation, package, or output task.

Uploads use the four states defined above. IMAGEGEN uses Generating, Succeeded, Failed, and cancellation cleanup; it never has Partial. Deterministic batch processing may finish Partially succeeded and shows succeeded/failed counts plus Retry failed items.

Silent provider retries remain in the running state. A failure is visible only after retry exhaustion. A failed task is cleaned immediately when the user deletes it, otherwise after 24 hours. Successful Activity and execution records may be deleted without deleting successful output images. Cancelled task cleanup removes invalid generated objects, temporary files, and intermediate data but never source assets or other tasks' outputs.

`retry-failed:{sourceTaskId}` is the stable business idempotency key for a derived retry. One source task creates at most one direct retry child for the same failed snapshot. Terminal source tasks remain immutable.

## Authentication

Only the username configured by `PIXFLOW_AUTH_ADMIN_USERNAME` may authenticate. Other database accounts may remain but login, refresh, and every JWT-authenticated request reject them as ordinary authentication failures.

Registration is not mapped on the backend and returns 404. The login page shows `暂未开放注册` without a form or request. JWT needs no role claim. Access tokens live only in memory; an HttpOnly refresh cookie restores the session on startup. All business routes require authentication.
