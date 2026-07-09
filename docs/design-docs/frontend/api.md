# PixFlow 前端 API 文档

> 本文档记录后端暴露给 Vue 前端的接口，依据 `design.md`、当前执行计划、`docs/design-docs/` 下各模块设计文档以及后端代码实现整理。
> 本文档有意排除 Agent 工具 schema、Java SPI、MQ consumer、内部 service API 和 provider adapter。

## 范围与约定

- 对外 HTTP 路由统一归一到 `/api` 前缀。全部 controller 真实挂载根前缀。`/api/auth/*`、`/api/conversations/*`、`/api/tasks/*`、`/api/files/packages/*`、`/api/downloads/*`、`/ws/progress`、`/api/rubrics/*`、`/api/messages` 都在网关下。
- Agent 长文本输出使用 SSE。任务进度与素材包解压进度使用 WebSocket/STOMP。
- 普通 JSON 响应统一走 `com.pixflow.common.web.ApiResponse`：成功 `success=true, code="OK"`；失败 `success=false, code=<ErrorCode>, message, data=null, details, traceId`。**注意：失败 envelope 中业务码字段名为 `code`，不是 `errorCode`；前端 client 同时兼容两种写法**（详见 `transport-api.md`）。
- 所有 HTTP / SSE / WS 帧应携带或能够关联 `traceId`。`X-Trace-Id` 由客户端 `client.ts` 自动注入；服务端在响应 / SSE 事件 / WS 帧回传，前端读取并提升到 `ApiError.traceId` 或 `useUiStore.floatingTraceId`。
- 真实执行确认提交请求必须带 `Idempotency-Key`，用于避免网络重试或用户重复点击导致重复创建任务。当前实现见 `ConfirmationController#submit`，前端使用 `confirm.ts` 自动按 `proposalId` 维护。
- **分页约定**：所有列表接口（`/conversations`、`/tasks`、`/tasks/{id}/results`、`/conversations/{id}/images`、`/files/packages`、`/files/packages/{id}/images`、`/files/packages/{id}/errors`、`/conversations/{id}/messages`、`/conversations/{id}/tasks`）均接受 `page`、`size` 两个 query。**`page` 必须从 `1` 开始**——服务端 `Pagination.of()` 强制校验 `page>=1`、`size∈[1,MAX_SIZE]`（`MAX_SIZE` 由各 controller 自定，前端默认遵守 `size<=100`），传 `page=0` 会收到 `INVALID_PARAM / 分页参数非法` (400)。分页响应字段为 `records/total/page/size`（不是 `items`）。前端 HTTP client 会自动把 `records` 重映射为 `items` 供前端代码读取，但仍建议前端 store 直接使用 `records` 与后端对齐。

## API 归属

| 范围 | 归属模块 | 前端接口形态 |
|---|---|---|
| 对话与 Agent 回合 | `module/conversation` | REST + SSE |
| HITL 确认 | `module/conversation` + `permission` + `task` | REST |
| 消息附件 / 素材包绑定 | `module/conversation` | 具体图片引用走 `POST /messages` 的 `attachments` 字段；素材包绑定走顶层 `packageId`（**不**暴露独立的 `/attachments` 端点） |
| 素材包 | `module/file` | REST + WS 进度 |
| 任务状态 / 结果 / 下载 | `module/task` 经 `app` 层 controller 暴露 | REST + WS 进度 |
| Rubrics 报告 | `module/rubrics` | REST |
| Trace 回放 | `harness/eval` | rubrics / debug 内部读 API；当前尚未定义公开前端 REST 路由 |

## Conversation API

> 后端 controller：`pixflow-conversation/.../api/ConversationController.java`、`MessageController.java`、`HistoryController.java`。
> 后端 envelope：`ApiResponse<业务payload>`。前端 HTTP client 自动解包到 `业务payload`。

### 会话 CRUD

| 方法 | 路径 | 用途 | 响应 payload |
|---|---|---|---|
| `POST` | `/api/conversations` | 创建会话。body 可为空 `{}`，也可携带 `{ title, packageId }`。 | `ConversationView`：`{ id, title, packageId, archived, createdAt, updatedAt }` |
| `GET` | `/api/conversations?includeArchived=false&page=1&size=20` | 查询会话列表，按 `updatedAt DESC`。 | `PageResponse<ConversationView>`：`{ records, total, page, size }` |
| `GET` | `/api/conversations/{conversationId}` | 查询会话元信息。 | `ConversationView`（见上） |
| `DELETE` | `/api/conversations/{conversationId}` | 软删除 / 归档会话（幂等）。 | `ApiResponse<null>` (200) |

> **字段名差异**：`ConversationView.id` 对应前端之前约定的 `conversationId`。前端 `ConversationSummary` / `ConversationDetail` 期望 `conversationId`，需要在 adapter 层做 `id → conversationId` 映射，或直接以后端字段为准。
> **分页参数**：`includeArchived` 缺省 `false`；`page` 必须 ≥1；`size` 必须 ≤100（`Pagination.of()` 校验，违反返 `400 INVALID_PARAM`）。前端 `store/conversations.ts:15` 现有调用 `listConversations({ archived: false, page: 0, size: 50 })` 会触发 400，需要改为 `page: 1` 或省略 `page` 让后端默认 `1`。
> **不存在的字段**：后端 `ConversationView` **没有** `images` 字段；该字段是前端 `ConversationSummary` 自定义扩展，调用方勿假定其存在。

### 发送消息并流式接收 Agent 回合

`POST /api/conversations/{conversationId}/messages`

**仅 JSON**（`MessageController#submit` 用 `@RequestBody`，不支持 multipart）。Content-Type：`application/json`。

请求体（`MessageSubmitRequest`）：

```json
{
  "prompt": "帮我把这批商品图统一成白底",
  "attachments": [],
  "packageId": "123",
  "metadata": { "trace": "..." }
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `prompt` | string | 否 | 用户文本 |
| `attachments` | `UserAttachmentInput[]` | 否 | 具体图片对象引用；V1 只接受 `UPLOAD_IMAGE`，不接受 `PACKAGE_REFERENCE` |
| `packageId` | string | 否 | 素材包绑定的唯一入口（**不是** `packageBinding`，也不是 `attachments[].PACKAGE_REFERENCE`）；后端 `TurnDispatchService.stream(...)` 直接读取并展开素材包图片 |
| `metadata` | object | 否 | 透传到 attachment collector 与 agent runner |

`UserAttachmentInput` 字段：`{ attachmentId?, type: 'UPLOAD_IMAGE', sourceRef, metadata? }`。`sourceRef` 必须是已上传对象引用；素材包引用只走顶层 `packageId`。

> **前端不一致**：
> - `api/messages.ts#SendMessageRequest` 字段名为 `packageBinding: { packageId: number }`，**后端不识别**，会被忽略并导致绑定失败。需要改为顶级 `packageId: string`。
> - `packageId` 是字符串（后端 `Long.parseLong` 解析），不是 number。
> - `attachments[]` 不再兼容 `PACKAGE_REFERENCE`；前端上传素材包后应只把返回的 packageId 写入顶层 `packageId`。
> - `attachments[].sourceRef` 后端 `Attachment` 构造时强制非空，前端若省略会导致 `IllegalArgumentException`。

响应：`200 text/event-stream`。

SSE 事件名（`SseAgentEventSink` 唯一投影点）：

| 事件 | Payload |
|---|---|
| `assistant_delta` | `{ "text": "...", "assistantCallId": "...", "modelTurnIndex": 1, "iteration": 1, "traceId": "...", "turnNo": 0 }` |
| `assistant_message_completed` | `{ "finalText": "...", "messageId": "...", "assistantCallId": "...", "modelTurnIndex": 1, "iteration": 1, "traceId": "...", "turnNo": 0 }` |
| `tool_call_ready` | `{ "toolName": "...", "toolCallId": "...", "toolInput": {...}, "assistantCallId": "...", "modelTurnIndex": 1, "iteration": 1, "traceId": "...", "turnNo": 0 }` |
| `tool_started` | `{ "toolCallId": "...", "toolName": "...", "assistantCallId": "...", "modelTurnIndex": 1, "iteration": 1, "traceId": "...", "turnNo": 0 }` |
| `tool_result` | `{ "toolCallId": "...", "toolName": "...", "content": "...", "metadata": {...}, "externalized": false, "error": false, "assistantCallId": "...", "modelTurnIndex": 1, "iteration": 1, "traceId": "...", "turnNo": 0 }` |
| `transition` | `{ "reason": "...", "assistantCallId": "...", "modelTurnIndex": 1, "iteration": 1, "traceId": "...", "turnNo": 0 }` |
| `completed` | `{ "finalText": "...", "assistantCallId": "...", "modelTurnIndex": 1, "iteration": 1, "traceId": "...", "turnNo": 0 }` |
| `error` | `{ "message": "...", "errorCode": "...", "traceId": "..." }`（当前后端错误帧至少有 `message`；`errorCode` / `traceId` 可选，前端按可选字段容错解析） |

SSE 说明：

- V1 不支持 `Last-Event-ID` 重连（`SseAgentEventSink` 不写入 `id:` 行）。
- 后端按 `pixflow.conversation.sse.heartbeat-interval`（默认 30s）发送 `: heartbeat` 注释帧；`MessageController` 使用独立调度器保活，并在完成、超时、错误路径取消 heartbeat。
- 任务进度不走该 SSE 流；前端应订阅下文 WebSocket 进度频道。
- `LOCK_ACQUISITION_FAILED`：前端发起新一轮 `/messages` 时若上一回合尚未结束（`conversationLock.tryLock(...)` 失败）会抛 `409 LOCK_ACQUISITION_FAILED`。前端应当在 store 层显式 abort 旧回合后再发新回合。

### 消息历史

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/conversations/{conversationId}/messages?page=1&size=50` | 读取用于展示的完整 transcript 时间线。**后端不接受 `sinceSeq` 参数**，前端发送的 `sinceSeq` 会被忽略。 |

响应：`ApiResponse<PageResponse<MessageView>>`，内层即 `PageResponse`：

```json
{
  "records": [
    {
      "id": "...",
      "seq": 1,
      "role": "USER",
      "content": "...",
      "toolCallId": null,
      "compactionMarker": null,
      "metadata": null,
      "attachedPackageId": null,
      "taskId": null,
      "createdAt": "...",
      "compactionBoundary": false
    }
  ],
  "total": 100,
  "page": 1,
  "size": 50
}
```

> **前端不一致**：`api/messages.ts#HistoryResponse` 期望字段 `messages / compressionMarkerCount / total`，与后端 `records / total / page / size` **不匹配**。HTTP client 的 `records → items` 重映射不能桥接 `messages`；需要把前端类型改为 `PageResponse<MessageView>` 风格并新增 `compactionBoundary`/`compactionMarker` 字段。

历史视图只读。`message` 表写入仍由 `harness/session` 负责。

## Attachment API

> **本节文档与实现不一致**：后端**没有** `POST /api/conversations/{conversationId}/attachments` 这个 endpoint。前端 `api/attachments.ts` 调用该路径会恒返 404。
>
> 后端 `MessageController#submit` 是 JSON-only，不能接收 multipart。当前会话附件**只能走以下两种路径之一**：
>
> 1. **引用已有素材包**：先通过 `module/file` 上传或选择素材包，随后 `POST /messages` body 只传顶层 `{ "packageId": "..." }`。后端按该 packageId 展开图片；不接受 `attachments[].PACKAGE_REFERENCE`。
> 2. **UPLOAD_IMAGE**：`attachments[]` 只放具体图片对象引用 `{ type: 'UPLOAD_IMAGE', sourceRef: '...' }`。V1 未提供从浏览器直传图片到 session 的 endpoint，普通浏览器上传应优先走素材包上传后绑定顶层 `packageId`。
>
> 已知错误码：
> - `ATTACHMENT_INVALID` (400)：`attachments[]` 中 `sourceRef` 为空或类型不是 `UPLOAD_IMAGE`。
> - `PACKAGE_REFERENCE_INVALID` (404)：引用的 `packageId` 不存在。
> - `LOCK_ACQUISITION_FAILED` (409)：上一回合尚未结束。
> - `TURN_RUNNER_UNAVAILABLE` (503) / `TASK_EXECUTION_UNAVAILABLE` (503)：agent runner / task executor 未装配。

> 旧文档曾重复描述 `POST /api/conversations/{conversationId}/attachments` 和 `sinceSeq` 历史查询参数；当前后端没有独立会话附件上传 endpoint，消息历史也不接受 `sinceSeq`。以上以“发送消息并流式接收 Agent 回合”和“消息历史”两节为准。

## HITL 确认 API

这些端点是前端触发真实图片处理或生图执行的唯一入口。Agent 工具只创建待确认提案。

### 获取二次确认 challenge 或直接 token

`POST /api/conversations/{conversationId}/confirm/{proposalId}/challenge`

**该接口被设计为可复用**：
- 第一次调用：请求体可空（`ConfirmationController#challenge` 不读 body），响应：
  - `needChallenge=true` + `challenge: ConfirmationChallenge`
  - `needChallenge=false` + `token: String`（**注意：后端 `ConfirmationChallengeResponse` 的 `token` 字段就是 `ConfirmationToken.tokenId`（`String`），前端 `ConfirmationToken` 类型期望 `{ token, expiresAt }` 是错的**——`expiresAt` 字段在线上不存在；只有当 `tokenService.issue(...)` 后续扩展 `expiresAt` 后才会带上）。
- 第二次调用（**V1 后端不读 body**）：再次调 `/challenge` 仍会返回 `needChallenge=true` + 同一个 challenge。当前**没有**"在 challenge 调用里传 answer 并直接返回 token" 的实现；正确的三步编排见下。

响应（`needChallenge=true`）：

```json
{
  "needChallenge": true,
  "challenge": {
    "challengeId": "...",
    "prompt": "该提案将处理 N 张图片，请输入“确认”继续。",
    "proposalId": "...",
    "conversationId": "...",
    "status": "PENDING",
    "issuedAt": "...",
    "expiresAt": "..."
  },
  "token": null
}
```

响应（`needChallenge=false`）：

```json
{
  "needChallenge": false,
  "challenge": null,
  "token": "uuid-string"
}
```

### 提交确认

`POST /api/conversations/{conversationId}/confirm/{proposalId}/submit`

Header：

| Header | 必填 | 用途 |
|---|---|---|
| `Idempotency-Key` | 是 | 防止重复创建任务。后端当前**未强制**该 header 的存在校验，但前端应保证一致。 |

请求体（`ConfirmationSubmitRequest`）：

```json
{
  "challengeId": "uuid-from-challenge-step",
  "challengeAnswer": "确认"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `challengeId` | 仅在 `requiresChallenge` 时必填 | 后端 `ConfirmationService#verifyChallenge` 会从 `Map<String, ConfirmationChallenge>` 中取出对应 challenge 校验是否过期。**前端必须在第二次 confirm 调用时回传这个 challengeId**。 |
| `challengeAnswer` | 仅在 `requiresChallenge` 时必填 | 答案必须命中 `pixflow.conversation.confirmation.permit-literal-answers`（默认 `["按实际", "按实际处理", "确认", "已确认"]`），否则 `PROPOSAL_CHALLENGE_FAILED`。 |

响应（`ConfirmationSubmitResponse`）：

```json
{
  "proposalId": "...",
  "taskId": "...",
  "status": "CONFIRMED"
}
```

> **前端不一致**（`useAgentTurn#doSubmit`）：当前调用 `submitConfirm(_, _, { challengeAnswer: undefined }, idemp)` **没有把 `pending.challengeId` 传给后端**，导致需要 challenge 的提案在 `/submit` 阶段被 `PROPOSAL_CHALLENGE_FAILED` 拒绝。修复：把 `challengeId: pending.challengeId` 一并写入 body。
> **类型不一致**：`api/confirm.ts#SubmitResponse` 期望 `{ taskId, status: 'PENDING' | 'RUNNING' }`，后端实际是 `{ proposalId, taskId, status: 'CONFIRMED' }`，`status` 是字符串而不是有限枚举。

重要错误码：

| HTTP | 错误码 | 含义 |
|---|---|---|
| `400` | `PROPOSAL_CHALLENGE_FAILED` | challenge 答案未通过 / `challengeId` 缺失。 |
| `409` | `PROPOSAL_CHALLENGE_EXPIRED` | challenge 已过期（`Map` 中不存在或 `expiresAt` 已过）。 |
| `400` | `CONFIRMATION_TOKEN_INVALID` | 后端 `ConfirmationService#issueToken` 失败（未实现命中路径，保留错误码以备扩展）。 |
| `404` | `PROPOSAL_NOT_FOUND` | 提案不存在或不属于该会话。 |
| `409` | `PROPOSAL_ALREADY_CONFIRMED` | 提案已确认。 |
| `409` | `CONVERSATION_ARCHIVED` | 会话已归档。 |

## Task API

任务创建不作为通用前端端点直接暴露；任务由确认提交触发。查询、取消和下载面向前端。

### 取消任务

`POST /api/conversations/{conversationId}/tasks/{taskId}/cancel`

响应（`CancellationResult`）：

```json
{ "taskId": "...", "cancelled": true }
```

> **前端不一致**：`api/tasks.ts#cancelTask` 期望 `{ cancelled: true }`，缺少 `taskId` 字段；TS 类型需要补充。

取消是协作式的。已经开始执行的工作单元不会被中途打断；worker 在单元边界检查取消标志。

### 任务查询与结果 API

task 设计中暴露 `TaskQueryService`，用于状态查询、会话任务历史、结果管理与下载。HTTP 路由由 app controller 承载，避免 task 模块直接承担跨模块 web 组合职责。

| 方法 | 路径 | 用途 | 后端能力 |
|---|---|---|---|
| `GET` | `/api/tasks/{taskId}` | 轮询任务状态。 | `TaskQueryService.getStatus` |
| `GET` | `/api/conversations/{conversationId}/tasks?page=1&size=20` | 查询会话任务历史面板。 | `TaskQueryService.listByConversation` |
| `GET` | `/api/tasks/{taskId}/results?page=1&size=50` | 查询任务结果列表，用于预览。 | `process_result` read model |
| `GET` | `/api/conversations/{conversationId}/images?page=1&size=100` | 查询会话下成功产物图片聚合列表，用于 `/files` 产物视图。 | `TaskQueryService.listConversationImages` |
| `GET` | `/api/tasks/{taskId}/downloads?resultId=...` | 获取单个结果的预签名下载 URL。 | `TaskQueryService.getResultDownload` |
| `GET` | `/api/tasks/{taskId}/downloads/bundle` | 构建 / 获取批量打包下载 URL。 | `DownloadBundleBuilder` |
| `DELETE` | `/api/tasks/{taskId}/results/{resultId}` | 隐藏单个任务产物（返 `ApiResponse<null>` 200，不是 204）。 | `TaskQueryService.deleteResult` |
| `PATCH` | `/api/tasks/{taskId}/results/{resultId}` | 修改单个任务产物展示名。 | `TaskQueryService.renameResult` |

**分页参数**：`TaskQueryController` 用 `@RequestParam(defaultValue = "0/1/20/50/100")`，但实际 `PageQuery` 验证 `page>=0`（**注意：task 模块的 `PageQuery` 接受 `page=0`，与 conversation 的 `Pagination` 不同**）；`size` 限定 1..200。文档应按 controller 实际默认（`listByConversation` 默认 `page=0, size=20`；`results` 默认 `page=0, size=50`；`images` 默认 `page=0, size=100`）对接，前端调用时显式传 `page=1` 等同于 backend `0+1`，行为可能与 session/list 接口不一致——以 controller 默认值为准更稳。

`TaskStatusView` payload：

```json
{
  "taskId": "...",
  "taskType": "IMAGE_PROCESS | IMAGE_GEN",
  "status": "PENDING | QUEUED | RUNNING | COMPLETED | FAILED | CANCELLED | PARTIAL",
  "progress": { "done": 320, "total": 800, "failed": 3 },
  "skipped": 0,
  "lastError": null,
  "createdAt": "...",
  "startedAt": "...",
  "finishedAt": "..."
}
```

> **前端不一致**：`api/tasks.ts#TaskStatusView` 当前缺少 `skipped` 与 `lastError` 字段，但这是后端必有字段。`TaskState.progress` 当前缺少 `skipped` 计数，会导致 `/files` 产物视图漏算。

`TaskSummary` payload：

```json
{
  "taskId": "...",
  "taskType": "IMAGE_PROCESS | IMAGE_GEN",
  "status": "...",
  "total": 800,
  "done": 320,
  "failed": 3,
  "createdAt": "...",
  "finishedAt": "..."
}
```

> **前端不一致**：`api/tasks.ts#listConversationTasks` 返回类型 `Page<TaskStatusView>` 是错的——后端 `PageResult<TaskSummary>` 字段为 `done/total/failed` 平铺（不是嵌套 `progress`）。需要改用 `TaskSummary` 类型。

下载响应返回 handle，不返回文件字节（`DownloadHandle`）：

```json
{
  "url": "https://minio-presigned-url",
  "expiresAt": "...",
  "contentType": "application/octet-stream",
  "sizeBytes": 223344
}
```

> **前端不一致**：`api/tasks.ts#DownloadHandle` 当前只有 `url`/`expiresAt`，缺 `contentType` 和 `sizeBytes`。这两个字段是后端真实下发，前端应在 UI 展示文件大小与类型时使用。

应用层不得代理结果图片字节；前端预览 / 下载应直接使用预签名 URL。

任务结果列表和会话产物聚合列表返回分页 payload（`PageResult<T>`：`records/total/page/size`）。普通结果列表会保留失败或跳过结果的状态与错误信息；会话产物聚合只返回可展示的成功产物图片。产物删除只设置 `process_result.deleted_at`，不删除对象存储字节；重命名只写 `display_name`，不改 `output_minio_key`。

`TaskResultView` payload：

```json
{
  "resultId": "456",
  "taskId": "99",
  "conversationId": "conv-1",
  "status": "SUCCESS | FAILED | SKIPPED",
  "kind": "BRANCH | GROUP | GENERATIVE",
  "imageId": "123",
  "skuId": "sku001",
  "groupKey": null,
  "viewId": "front",
  "branchId": "branch-main",
  "filename": "sku001_branch-main.png",
  "displayName": null,
  "size": 223344,
  "url": "https://minio-presigned-url",
  "createdAt": "...",
  "finishedAt": "...",
  "errorMsg": null
}
```

> **字段枚举值**：前端 `api/tasks.ts#TaskResult.status` 写的是 `'SUCCESS' | 'FAILED' | 'SKIPPED'`，与后端 `ResultStatus` 枚举一致；`kind` 是 `'BRANCH' | 'GROUP' | 'GENERATIVE'`（与文档示例一致）。

重命名请求体：

```json
{ "displayName": "new-name.png" }
```

### 自定义下载打包

`POST /api/downloads/bundle`

该接口由 app 层组合素材图片和任务产物，支持 `/files` 页面下载任意选中集合。后端解析每个 item 到对象存储位置，生成临时 ZIP 后返回预签名 URL。当前实现复用既有 `DownloadBundleBuilder`，仍是内存构建后写入临时 bucket，后续大批量下载应单独优化为流式写入。

请求（`CustomBundleRequest`）：

```json
{
  "items": [
    { "type": "ASSET_IMAGE", "imageId": "123", "filename": "source-front.png" },
    { "type": "TASK_RESULT", "resultId": "456", "filename": "output-front.png" }
  ],
  "archiveName": "selected-images.zip"
}
```

响应（`DownloadHandle`）：

```json
{
  "url": "https://minio-presigned-url",
  "expiresAt": "2026-07-04T08:30:00Z",
  "contentType": "application/zip",
  "sizeBytes": 123456
}
```

## WebSocket / STOMP API

### 连接

- HTTP 端点：`WS /ws/progress`（`ProgressMessagingConfig#registerStompEndpoints`，允许跨域 `*`）
- STOMP broker prefix：`/topic`，app destination prefix：`/app`
- STOMP CONNECT 鉴权：`AuthWebSocketInterceptor` 读取 STOMP native header `Authorization: Bearer <token>` 或 `X-Auth-Token`，**不**读 query 参数 token。前端 `transport/ws.ts` 已通过 `connectHeaders` 注入，**不要**改成 query 形式。

### 任务进度频道

> 实际 topic 由 `ConversationProgressBridge` 拼装：`/topic/{prefix}-{conversationId}-{taskId}`，prefix 默认 `task-progress`（配置项 `pixflow.conversation.progress.topic-prefix`）。**分隔符是 `-`，不是 `/`**。前端应使用：
>
> ```
> /topic/task-progress-{conversationId}-{taskId}
> ```

推送帧（`ProgressEvent`，**平铺字段**，无嵌套 `progress` 对象）：

```json
{ "taskId": "...", "done": 320, "total": 800, "failed": 3, "skipped": 0, "status": "RUNNING", "occurredAt": "..." }
```

```json
{ "taskId": "...", "status": "COMPLETED", "occurredAt": "..." }
```

```json
{ "taskId": "...", "status": "CANCELLED", "occurredAt": "..." }
```

> **前端不一致**：`useTask.applyWsFrame` 期望字段 `completedAt` / `cancelledAt`，**后端真实字段是 `occurredAt`**（`ProgressEvent.occurredAt`，`Instant`）。需要把前端代码 `frame.completedAt` / `frame.cancelledAt` 改为 `frame.occurredAt`，并把整帧放进 `state.finishedAt`。
> **前端不一致**：`api.md` 老版本用 `/` 分隔（`/topic/task-progress/{cid}/{tid}`），实现与上文不一致；以本文档当前行 `/topic/task-progress-{cid}-{tid}` 为准。

说明：`module/task.md` 也提到逻辑订阅 `/task/{taskId}/progress`，该路径**未实际订阅**。前端传输层应统一使用上面的 STOMP endpoint 与 topic；task 只发布 `ProgressEvent`，不拥有 WebSocket 连接。

### 素材包进度频道

订阅：

`/topic/packages/{packageId}/progress`

推送帧（`ExtractionProgress`）：

```json
{
  "packageId": 123,
  "extracted": 120,
  "total": 300,
  "status": "EXTRACTING"
}
```

## Asset Package API

`module/file.md` 明确定义了素材包上传，并描述了通过 `FileController` 提供包详情 / 列表 / 删除。下表路径沿用现有 `/api/files/packages` 前缀。

**V1 采用分片上传协议**（5MB 分片 + SHA-256 整包 hash + uploadId 续传 + 同 fileHash 互斥锁）。整文件 `multipart` 上传作为兼容端点保留。

### 端点总览

| 方法 | 路径 | 用途 |
|---|---|---|
| `POST` | `/api/files/packages/init` | 初始化上传会话。整包 hash 命中已存在的 READY 包时直接返回 `packageId`（整包去重）。 |
| `GET` | `/api/files/packages/sessions/{uploadId}` | 拉取上传会话状态（已传分片、剩余分片、总分片数）。供断线重连 / 刷新续传使用。 |
| `PUT` | `/api/files/packages/sessions/{uploadId}/chunks/{index}` | 上传单个分片（5MB，body 为原始字节）。 |
| `POST` | `/api/files/packages/sessions/{uploadId}/complete` | 通知分片全部上传完成。后端校验完整性、调 MinIO 拼接 API、落 `asset_package`、发解压消息。 |
| `DELETE` | `/api/files/packages/sessions/{uploadId}` | 取消上传会话：清理 Redis 元数据 + MinIO 临时分片，幂等。 |
| `POST` | `/api/files/packages` | **整文件上传兼容端点**（V1 保留，V2 可下）。Content-Type `multipart/form-data`。 |
| `GET` | `/api/files/packages/{id}` | 查询素材包详情与解压进度，作为轮询兜底。 |
| `GET` | `/api/files/packages?page=1&size=20` | 查询素材包列表。**`page` 从 1 开始**（`FileController` 用 `Pagination` / 自有 `PageQuery`，`page=0` 会触发 `INVALID_PARAM`）。 |
| `DELETE` | `/api/files/packages/{id}` | 删除素材包：未被引用则物理删除，否则软删除（返 `ApiResponse<null>` 200）。 |
| `GET` | `/api/files/packages/{id}/errors?page=1&size=20` | 查询导入 / 解压错误明细。 |
| `GET` | `/api/files/packages/{id}/images?page=1&size=50` | 查询素材包内图片列表，返回预签名预览 URL。 |
| `DELETE` | `/api/files/packages/{id}/images/{imageId}` | 隐藏素材包内单张图片（返 200）。 |
| `PATCH` | `/api/files/packages/{id}/images/{imageId}` | 修改素材图片展示名（请求体 `{ displayName }`）。 |

> **路径参数名**：`/api/files/packages/{id}` 使用 `{id}`（不是 `{packageId}`）；`/api/files/packages/{id}/images/{imageId}` 两个都是数字 `long`。前端 `api/packages.ts` 当前用 `packageId: number` 与 `imageId: string`，与 controller `@PathVariable("id") long id` + `@PathVariable("imageId") long imageId` 不一致：`imageId` 是 number 不是 string，前端需要改类型。

素材图片列表返回分页 payload。删除只设置 `asset_image.deleted_at`，不删除 MinIO 对象；重命名只写 `display_name`，不改 zip 内原始路径 `original_path`。

`AssetImageView` 真实字段（`AssetImageView.java:6-17`）：

```json
{
  "imageId": "123",
  "packageId": 10,
  "filename": "front.png",
  "displayName": null,
  "originalPath": "sku001/front.png",
  "skuId": "sku001",
  "groupKey": null,
  "viewId": "front",
  "size": 123456,
  "url": "https://minio-presigned-url",
  "createdAt": "2026-07-04T08:00:00Z"
}
```

`AssetPackage` 真实字段（`AssetPackage.java:8-23`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `Long` | 主键；**前端应改称 `packageId` 但 controller 序列化时仍使用 `id`** |
| `name` | `String` | 用户可见名 |
| `fileHash` | `String` | 整包 SHA-256（用于去重） |
| `minioZipKey` | `String` | 源 zip key |
| `docKey` | `String` | 文案对象 key（可空） |
| `status` | `PackageStatus` | `UPLOADED | EXTRACTING | READY | PARTIAL | FAILED` |
| `imageCount` | `Integer` | |
| `extractedCount` | `Integer` | |
| `errorSummary` | `String` | |
| `deletedAt` | `Instant` | 软删时间 |
| `createdAt` | `Instant` | |
| `updatedAt` | `Instant` | |

> **前端不一致**：`types/upload.ts#PackageDetail` 当前期望 `packageId: number`，而后端 JSON 字段是 `id`。前端需要把 `id` 映射为 `packageId`（如 `api/packages.ts` 改 adapter，或在 client.ts 增加 `normalizePayload` 规则）。
> `PackageDetail` 文档示例中 `imageCount / extractedCount / errorSummary` 都有；`name` 字段前端未声明，需要补上。

重命名请求体：

```json
{ "displayName": "new-name.png" }
```

### 分片上传协议

#### 1. 初始化

`POST /api/files/packages/init`

请求体（`InitUploadRequest`）：

```json
{
  "filename": "summer_dresses.zip",
  "size": 104857600,
  "fileHash": "sha256-hex-of-whole-file",
  "chunkSize": 5242880
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `filename` | 是 | 文件名（含扩展名）。 |
| `size` | 是 | 整包字节数（long）。 |
| `fileHash` | 是 | 整包 SHA-256（hex）。用于整包去重 + 续传定位。 |
| `chunkSize` | 是 | 客户端声明的分片大小（long）。V1 固定 `5242880`（5MB）。后端**必须**校验与 `pixflow.upload.chunk-size`（配置项）一致，不一致返 `400 CHUNK_SIZE_MISMATCH`；协议刚性，不允许协商。 |

init 阶段错误码（**注意**：`FILE_HASH_INVALID` / `FILE_SIZE_OUT_OF_RANGE` / `UPLOAD_RATE_LIMITED` / `FILE_HASH_ALREADY_EXISTS` 这几个**老文档里的码在 `FileErrorCode` 中并不存在**，前端上传 worker 不应再按这些分支判断）：

| HTTP | 错误码（`FileErrorCode`） | 含义 |
|---|---|---|
| `400` | `CHUNK_SIZE_MISMATCH` | 客户端 `chunkSize` 与服务端配置不一致。 |
| `400` | `CHUNK_HASH_MISMATCH` | 整包 hash 校验失败（`init` 走 `FILE_HASH_MISMATCH` 是旧命名，**实际为 `CHUNK_HASH_MISMATCH`** ——前端若用 `FILE_HASH_MISMATCH` 需改为 `CHUNK_HASH_MISMATCH`） |
| `400` | `UPLOAD_TOO_LARGE` | `size` 超出 `pixflow.upload.max-zip-size` 上限。 |
| `409` | `PACKAGE_DEDUP_CONFLICT` | 同 fileHash 已有正在上传的会话（前端应作为提示，不视为致命错误）。 |

> 整包去重命中会**成功**返回 `InitUploadResponse.mode="DEDUP"` 而不是错误码。

响应（**新建会话**）：

```json
{
  "mode": "UPLOAD",
  "uploadId": "uuid-v4-string",
  "packageId": null,
  "status": null,
  "chunkSize": 5242880,
  "expectedChunks": 20,
  "uploadedChunks": []
}
```

响应（**整包去重命中**）：

```json
{
  "mode": "DEDUP",
  "uploadId": null,
  "packageId": 123,
  "status": "READY",
  "chunkSize": 0,
  "expectedChunks": 0,
  "uploadedChunks": []
}
```

前端在 `mode=DEDUP` 时直接停止上传、提示"该压缩包已存在"，不进入分片上传阶段。

响应（**同 fileHash 正在被其他客户端上传**）：

```json
{
  "mode": "DEDUP",
  "uploadId": "existing-uuid",
  "packageId": null,
  "status": "UPLOADING",
  "chunkSize": 0,
  "expectedChunks": 0,
  "uploadedChunks": [0, 1, 2, 3]
}
```

此时前端可选择：(a) 直接把 `existing-uuid` 作为自己的 `uploadId` 续传（被同 fileHash 互斥锁保护，安全）；(b) 提示"另一上传正在进行"让用户取消再试。

> **同 fileHash 互斥锁**：后端在 `init` 阶段取 `Redisson 锁 lock:package-upload:{fileHash}`（看门狗续期，TTL 与 `session.ttl` 对齐），校验"同 fileHash 是否已有未完结 `upload_session`"。如有则进入 `DEDUP/UPLOADING` 分支；锁内创建 `upload_session` 后释放。后续 `chunks/*` 与 `complete` 不再持该锁，避免长时间阻塞。

#### 2. 拉取会话（断点续传用）

`GET /api/files/packages/sessions/{uploadId}`

响应（`UploadSessionState`，**9 个字段，没有 `createdAt` / `updatedAt`**）：

```json
{
  "uploadId": "uuid",
  "fileHash": "sha256-hex",
  "size": 104857600,
  "chunkSize": 5242880,
  "expectedChunks": 20,
  "uploadedChunks": [0, 1, 2, 3, 5, 6],
  "failedChunks": [],
  "status": "UPLOADING | READY | EXPIRED | CANCELLED",
  "packageId": null
}
```

`status=READY` 表示该会话已完成压缩包拼接并落了 `asset_package`，响应里 `packageId` 非空；前端直接跳到解压进度订阅。

`status=EXPIRED` / `CANCELLED` 表示会话已失效，前端应丢弃本地缓存并提示用户重新上传。

> **前端不一致**：`types/upload.ts#UploadSessionState` 字段定义一致；`failedChunks` 实际后端 `UploadSessionService.toState` 中固定返回 `List.of()`，**不会**真实回填失败分片。前端 worker 应主要靠本地状态判断分片失败。

#### 3. 上传分片

`PUT /api/files/packages/sessions/{uploadId}/chunks/{index}`

| Header | 必填 | 说明 |
|---|---|---|
| `Content-Type` | 是 | `application/octet-stream`（controller 显式 `consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE`）。 |
| `X-Chunk-Hash` | 是 | 分片 SHA-256（hex）。后端校验，不一致返 4xx。 |
| `X-Chunk-Size` | 是 | 分片字节数（long，与 body 长度一致）。 |

Body：分片原始字节。

响应（`PutChunkResponse`，**成功**）：

```json
{
  "uploadId": "uuid",
  "index": 5,
  "status": "ACCEPTED",
  "uploadedChunks": [0, 1, 2, 3, 4, 5]
}
```

响应（**分片已存在**，幂等）：

```json
{
  "uploadId": "uuid",
  "index": 5,
  "status": "ALREADY_EXISTS",
  "uploadedChunks": [0, 1, 2, 3, 4, 5]
}
```

前端对 `ALREADY_EXISTS` 不视为错误（断点续传常见）。

重要错误码：

| HTTP | 错误码 | 含义 |
|---|---|---|
| `400` | `CHUNK_HASH_MISMATCH` | 分片 hash 与 body 不一致，客户端应重新读取并重传。 |
| `400` | `CHUNK_SIZE_MISMATCH` | 头声明分片大小与 body 长度不一致。 |
| `400` | `CHUNK_OUT_OF_RANGE` | `index` 超出 `expectedChunks` 范围。 |
| `404` | `UPLOAD_SESSION_NOT_FOUND` | `uploadId` 不存在或已失效。 |
| `409` | `UPLOAD_SESSION_NOT_UPLOADING` | 会话已 `READY` / `CANCELLED` / `EXPIRED`，不可写分片。 |

> **没有令牌桶限流**：`UPLOAD_RATE_LIMITED` 不在 `FileErrorCode` 中。**前端 chunkWorker 不应继续按 429 走退避**；现有 5xx / 网络错误重试逻辑保留。

> **分片去重 / 锁**：`PUT /chunks/{index}` 内部取 `Redisson 锁 lock:package-chunk:{uploadId}:{index}`（短时锁，毫秒级，**不**启看门狗），校验 `chunk:{uploadId}:{index}` 是否已存在；不存在则写 MinIO 临时桶 + Redis 元数据，**MinIO 上传使用 `CopyObject` 或 `PutObject` 走单写语义**（依赖 MinIO 的 `If-None-Match: *` 实现单写）；存在则比对 hash，一致返 `ALREADY_EXISTS`，不一致返 `CHUNK_HASH_MISMATCH` 提示重传。

#### 4. 完成上传

`POST /api/files/packages/sessions/{uploadId}/complete`

请求体（`CompleteUploadRequest`，**可选**校验字段）：

```json
{
  "fileHash": "sha256-hex-of-whole-file"
}
```

响应（**成功**，`CompleteUploadResponse`）：

```json
{
  "packageId": 123,
  "status": "UPLOADED"
}
```

响应（**分片不齐**）：

```json
{
  "code": "INCOMPLETE_CHUNKS",
  "message": "分片不完整",
  "details": {
    "expected": 20,
    "uploaded": 18,
    "missing": [4, 11]
  }
}
```

响应（**整包 hash 不一致**）：

```json
{
  "code": "FILE_HASH_MISMATCH"
}
```

> **complete 锁**：`POST /complete` 取 `Redisson 锁 lock:package-complete:{uploadId}`（短时锁，毫秒级，**不**启看门狗）。锁内做：(1) 校验分片总数 == `expectedChunks`；(2) 校验整包 hash（如果客户端传入）；(3) 调 MinIO `composeObject` 拼接分片为完整 zip 到 `packages/{packageId}/source.zip`；(4) 校验拼接结果大小与 hash；(5) 落 `asset_package(status=UPLOADED)` + `package_upload_session.status=READY`；(6) 发 RocketMQ 解压消息；(7) 删 MinIO 临时桶 `tmp-uploads/{uploadId}/*`；(8) 释放锁。

#### 5. 取消上传

`DELETE /api/files/packages/sessions/{uploadId}`

幂等。对不存在的 `uploadId` 也返 200。

响应（`CancelUploadResponse`）：

```json
{
  "uploadId": "uuid",
  "status": "CANCELLED"
}
```

> **取消锁**：后端在 `DELETE` 内部取 `Redisson 锁 lock:package-cancel:{uploadId}`（短时锁），幂等置 `upload_session.status=CANCELLED`，删 Redis 元数据 + 删 MinIO 临时桶。**正在并发上传中的分片会因 `UPLOAD_SESSION_NOT_UPLOADING` 409 自然失败**，客户端 worker 据此收敛。

### 整文件上传兼容端点

`POST /api/files/packages`

Content-Type：`multipart/form-data`

| 字段 | 必填 | 说明 |
|---|---|---|
| `zip` | 是 | 源图片 zip（`@RequestPart("zip")`）。 |
| `doc` | 否 | CSV/XLSX 文案文档（`@RequestPart(value = "doc", required = false)`）。 |

响应（`UploadPackageResponse`）：

```json
{
  "packageId": 123,
  "status": "UPLOADED | EXTRACTING",
  "messageConfirmed": true
}
```

**V1 推荐统一走分片协议**；此端点保留用于：(1) 极小文件场景的快速通道；(2) 其他客户端（CLI、脚本）兼容性。

### 素材包详情响应

`AssetPackage` 序列化字段（见上），不包含 `images` 集合；如需图片列表调用 `GET /api/files/packages/{id}/images`。

### 去重 / 锁 / 限流总览

| 维度 | 机制 | 责任方 |
|---|---|---|
| **整包去重** | `asset_package.file_hash` 唯一索引 + init 阶段查重 | 后端 |
| **同 fileHash 上传互斥** | `Redisson 锁 lock:package-upload:{fileHash}`（看门狗续期） | 后端 |
| **分片去重（幂等）** | `chunk:{uploadId}:{index}` Redis 元数据 + MinIO 单写语义 | 后端 |
| **分片并发写保护** | `Redisson 锁 lock:package-chunk:{uploadId}:{index}`（短时锁，毫秒级） | 后端 |
| **complete 防并发拼接** | `Redisson 锁 lock:package-complete:{uploadId}`（短时锁） | 后端 |
| **cancel 防并发清理** | `Redisson 锁 lock:package-cancel:{uploadId}`（短时锁） | 后端 |
| **上传速率限制** | **未实现**。`UPLOAD_RATE_LIMITED` / `Retry-After` 不在 `FileErrorCode` 中，前端应移除相关退避分支。 | — |
| **客户端限流** | 前端 worker 池（默认 4 并发） | 前端 |

### 上传会话存储与生命周期

`package_upload_session` **仅存 Redis**（不落 MySQL），结构：

| Redis Key | 类型 | 内容 | 生命周期 |
|---|---|---|---|
| `upload:session:{uploadId}` | Hash | `fileHash`, `filename`, `size`, `chunkSize`, `expectedChunks`, `status`, `packageId`, `createdAt`, `updatedAt` | TTL = `pixflow.upload.session.ttl`（默认 24h），过期即视为 `EXPIRED` |
| `upload:session:{uploadId}:chunks` | Set | 已上传分片 `index` 集合 | 与 session 同生命周期 |
| `upload:lock:filehash:{fileHash}` | String | 同 fileHash 互斥锁占位（Redisson 看门狗续期） | 与 active session 绑定，session 终止即释放 |
| `upload:chunk:{uploadId}:{index}` | Hash | `{chunkHash, chunkSize, minioKey}` 单分片元数据 | 与 session 同生命周期 |
| `tmp-uploads/{uploadId}/...` | MinIO 对象 | 分片原始字节 | complete 成功或 cancel / expire 时清理 |

**状态机**：

```
UPLOADING ──complete──► READY        （complete 成功，落 asset_package）
UPLOADING ──cancel ───► CANCELLED    （DELETE 调用）
UPLOADING ──ttl 过期──► EXPIRED      （Redis TTL 到期，由 init 阶段按 fileHash 重新创建）
READY     ─────────────► (终态)      （不再接受 PUT 分片）
CANCELLED ─────────────► (终态)
EXPIRED   ─────────────► (终态)
```

**为什么 Redis only**：单用户单机场景下 Redis 已是事实源；`asset_package.file_hash` 唯一索引提供"重启后还能识别同 hash"的能力，崩溃后用户重新 init 即恢复。MySQL 表化增加复杂度收益不抵。

### 上传相关错误码总览

| 错误码 | HTTP | 阶段 | 含义 | 旧文档中是否有 |
|---|---|---|---|---|
| `CHUNK_SIZE_MISMATCH` | 400 | init / chunk PUT | 客户端 `chunkSize` 与服务端配置不一致；或分片 body 长度与 `X-Chunk-Size` 不一致 | 是 |
| `CHUNK_HASH_MISMATCH` | 400 | chunk PUT / init 哈希校验 | 分片 hash 与 body 不一致；旧版 `FILE_HASH_MISMATCH` 实为此码 | **合并** |
| `CHUNK_OUT_OF_RANGE` | 400 | chunk PUT | `index` 超出 `expectedChunks` 范围 | 是 |
| `UPLOAD_TOO_LARGE` | 400 | init | `size` 超出上限 | **新名**（替代 `FILE_SIZE_OUT_OF_RANGE`） |
| `INCOMPLETE_CHUNKS` | 400 | complete | 分片总数不齐 | 是 |
| `UPLOAD_SESSION_NOT_FOUND` | 404 | 任何 session 接口 | `uploadId` 不存在或已 TTL 过期 | 是 |
| `UPLOAD_SESSION_NOT_UPLOADING` | 409 | chunk PUT | session 已是 READY / CANCELLED / EXPIRED | 是 |
| `PACKAGE_DEDUP_CONFLICT` | 409 | init | 同 fileHash 已有正在上传的会话（应通过 `mode=DEDUP/UPLOADING` 200 路径传达） | **新名**（替代 `FILE_HASH_ALREADY_EXISTS`） |
| `PACKAGE_NOT_FOUND` | 404 | 任何 package 接口 | packageId 不存在 | — |
| `ASSET_IMAGE_NOT_FOUND` | 404 | image 接口 | imageId 不存在或不属于该 package | — |
| `ASSET_IMAGE_NAME_INVALID` | 400 | rename | displayName 非法 | — |
| `INVALID_ZIP` | 400 | multipart upload | 上传 zip 解析失败 | — |
| `ZIP_BOMB_DETECTED` / `ZIP_PATH_TRAVERSAL` | 409 | multipart upload | 安全校验失败 | — |
| `NO_VALID_IMAGE` | 409 | multipart upload | zip 内无有效图片 | — |
| `UNSUPPORTED_IMAGE_FORMAT` | 400 | multipart upload | 包含不支持的图片格式 | — |
| `COPY_DOC_PARSE_FAILED` | 400 | multipart upload | CSV/XLSX 文案文档解析失败 | — |
| `PACKAGE_ALREADY_REFERENCED` | 409 | delete package | 包被引用无法物理删除（仅软删） | — |
| `MESSAGE_PUBLISH_FAILED` | 503 | complete | 发解压消息失败 | — |

**已删除的旧错误码**（前端若仍按这些分支判断需要移除）：`FILE_HASH_INVALID`、`FILE_SIZE_OUT_OF_RANGE`、`UPLOAD_RATE_LIMITED`、`FILE_HASH_ALREADY_EXISTS`。

## Rubrics API

`module/rubrics` 属于 Wave 6，明确提供前端 / PM 自助接口。实际 controller 路径在 `pixflow-module-rubrics`：

- `RubricsReportController`（读侧：templates / runs / scores / alerts / baselines）
- `RubricsAdminController`（管理侧：写入 baselines / runs）

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/rubrics/templates` | 列出所有 rubric 模板及版本。 |
| `GET` | `/api/rubrics/templates/{id}/versions` | 查询单个模板的版本列表。 |
| `POST` | `/api/rubrics/runs` | 手动启动一次评估运行。 |
| `GET` | `/api/rubrics/runs` | 查询评估运行历史。 |
| `GET` | `/api/rubrics/runs/{id}` | 查询运行详情与进度。 |
| `GET` | `/api/rubrics/runs/{id}/regression?baselineRunId=...` | 与指定基线进行回归对比。 |
| `GET` | `/api/rubrics/scores/by-result/{resultId}` | 按 `process_result` 查询评分明细。 |
| `GET` | `/api/rubrics/scores/by-sku/{skuId}` | 按 SKU 查询评分历史。 |
| `GET` | `/api/rubrics/baselines` | 查询基线列表。 |
| `POST` | `/api/rubrics/baselines` | 创建或替换基线。 |
| `GET` | `/api/rubrics/alerts` | 查询评分预警列表。 |

手动运行请求：

```json
{
  "templateId": "image_quality",
  "templateVersion": "1.0.0",
  "resultIds": [1001, 1002],
  "baselineRunId": "optional"
}
```

Rubrics 评分规则：

- LLM judge 只返回 `PASS` / `FAIL`，以及置信档位和理由。
- 数值分数由后端程序计算，并以 `overallScore`、domain score、dimension detail 返回。

## Commerce Data API

`module/commerce` 目前由 Agent 工具（`search` / `read`）消费，尚未定义公开前端 REST 端点。前端用户通过对话流程间接访问电商数据。

若未来增加 admin 导入 UI，应在本文补充对应接口，并由 `CommerceImportService.importData(...)` 承载。

## Eval / Trace API

`harness/eval` 定义了读侧查询与回放服务（`TraceQuery`、`TraceReplay`），供 rubrics 与 debug 使用，但设计文档中尚未定义公开前端 controller。

在明确设计 debug UI 前，不应暴露原始 `agent_trace`。即使经过脱敏，trace 仍可能包含 prompt、工具调用和记忆召回上下文等敏感信息。

## Auth API

> Auth controller 在 `pixflow-app/auth/AuthController.java`。鉴权由 `pixflow-infra-auth` 提供 JWT / Refresh cookie 机制。

| 方法 | 路径 | 用途 |
|---|---|---|
| `POST` | `/api/auth/register` | 注册并返回 access token + 设置 refresh cookie。body：`{ username, password, displayName? }` |
| `POST` | `/api/auth/login` | 登录并返回 access token + 设置 refresh cookie。body：`{ username, password }` |
| `POST` | `/api/auth/refresh` | 无 body；从 cookie `PIXFLOW_REFRESH` 读 refresh token，旋转新 access token + cookie。 |
| `POST` | `/api/auth/logout` | 无 body；从 cookie 读 refresh token + Authorization 头读 access token；撤销 Redis 会话 + 拉黑 access jti。 |
| `GET` | `/api/auth/me` | 读取当前用户。需带 Authorization。 |

成功响应（`AuthTokenPayload`）：

```json
{
  "accessToken": "jwt",
  "accessTokenExpiresAt": "2026-07-06T12:15:00Z",
  "user": { "userId": 1, "username": "...", "displayName": "...", "status": "ACTIVE" }
}
```

**`refreshToken` 不在响应 body 中**，只通过 `Set-Cookie: PIXFLOW_REFRESH=...; HttpOnly; SameSite=Lax; Path=/; Max-Age=2592000` 下发；前端不能也不应接触 refresh token。

`AuthErrorCode` 与 HTTP 状态：

| 错误码 | HTTP | 含义 |
|---|---|---|
| `AUTH_USERNAME_TAKEN` | 409 | 用户名已存在 |
| `AUTH_INVALID_CREDENTIALS` | 401 | 用户名 / 密码错误 |
| `AUTH_TOKEN_MISSING` | 401 | 缺 Authorization 头 |
| `AUTH_TOKEN_INVALID` | 401 | JWT 签名 / 格式错误 |
| `AUTH_TOKEN_EXPIRED` | 401 | JWT 过期（HTTP client 自动 refresh 重试一次） |
| `AUTH_TOKEN_REVOKED` | 401 | access token jti 已被拉黑（logout） |
| `AUTH_REFRESH_EXPIRED` | 401 | refresh cookie 过期 |
| `AUTH_REFRESH_INVALID` | 401 | refresh cookie 无效或被撤销 |
| `AUTH_ACCOUNT_DISABLED` | 403 | 账号被禁用 |
| `AUTH_TOO_MANY_ATTEMPTS` | 429 | 登录失败超过 5 次 / 10 分钟（`X-Forwarded-For` 优先） |
| `AUTH_USERNAME_INVALID` / `AUTH_PASSWORD_INVALID` | 400 | validation 失败 |

**配置常量**（默认值）：

- access token TTL：15 分钟
- refresh token TTL：30 天（cookie Max-Age）
- 登录失败上限：5 次 / 10 分钟；同 username 与同 IP（`X-Forwarded-For` 优先）独立计数；block 10 分钟
- access token / refresh token 存储：access 走 JWT（HS256，issuer `pixflow`）；refresh 不做 JWT，**只存 SHA-256 hash 在 Redis** `auth:refresh:{jti}`
- access token blacklist：登出后写入 `auth:blacklist:access:{jti}`，TTL = 剩余寿命
- cookie 名称：`PIXFLOW_REFRESH`（可通过 `pixflow.auth.refresh.cookie-name` 覆盖）

> **前端不一致**：`api/auth.ts#AuthUser.id?: string` 后端不存在该字段——后端 `UserView` 只有 `userId`（Long），没有 `id`。`authSession.ts#toUser` 已经在用 `userId ?? username` 兜底，但应清理误导性类型。

## 通用错误码（CommonErrorCode）

> `pixflow-common/src/main/java/com/pixflow/common/error/CommonErrorCode.java:6-19`

| 错误码 | 默认 HTTP | 含义 |
|---|---|---|
| `INTERNAL_ERROR` | 500 | 兜底 |
| `INVALID_PARAM` | 400 | 参数非法（如分页 `page<1` 或 `size>MAX_SIZE`） |
| `RESOURCE_NOT_FOUND` | 404 | 通用 404 |
| `PERMISSION_DENIED` | 403 | 通用 403 |
| `BUSINESS_RULE_VIOLATION` | 409 | 通用业务规则 |
| `DEPENDENCY_UNAVAILABLE` | 503 | 下游依赖不可用 |
| `RATE_LIMITED` | 429 | 通用限流（目前只有 auth 模块用） |
| `NETWORK_TIMEOUT` | 504 | 网络超时 |
| `PROVIDER_FAILURE` | 502 | 第三方 provider 失败 |
| `CONTEXT_LIMIT_EXCEEDED` | 400 | 上下文超限（agent 用） |
| `STORAGE_FAILURE` | 500 | 存储失败 |
| `IMAGE_PROCESSING_FAILURE` | 422 | 图像处理失败 |
| `TOOL_FAILURE` | 500 | 工具执行失败 |

**信封 schema**：见 `base/common.md` 与 `ApiResponse` record：

```json
{
  "success": true,
  "code": "OK",
  "data": { ... },
  "details": null,
  "traceId": null
}
```

失败时：

```json
{
  "success": false,
  "code": "INVALID_PARAM",
  "message": "分页参数非法",
  "details": { "page": 0, "size": 50, "maxSize": 100 },
  "traceId": "..."
}
```

`@JsonInclude(NON_NULL)` 使 `null` 字段不出现在 JSON 中——前端解析时**不应假设所有字段都在**。

## 非前端 API

以下内容明确不是浏览器前端接口：

- Agent 工具：`search`、`read`、`agent`、`submit_image_plan`、`submit_imagegen_plan`、`plan`、`plan_exit`。
- DAG 像素工具：`remove_bg`、`set_background`、`resize`、`compress`、`watermark`、`convert_format`、`generate_copy`、`compose_group`。
- Java 模块 API，例如 `TaskCommandService`、`DagValidator`、`ImageGenExecutor`、`VisionService`、`TraceRecorder`、`CheckpointReadPort`。
- MQ queue、DLQ listener、定时恢复扫描、后台 consumer。
- AI、storage、vector DB、remove-bg、电商平台集成等第三方 / provider adapter。

## 待确认路由决策

以下路径由模块 controller / service 推导而来，实际实现 web controller 时需要最终确认：

| 范围 | 待决策项 |
|---|---|
| Eval trace 回放 | 判断是否需要前端 debug console；若需要，应单独设计脱敏后的 trace 查询 / 回放端点。 |
| Attachment 上传 | 已收敛：V1 `POST /messages` 不支持 multipart；前端 `api/attachments.ts#uploadAttachment()` 复用素材包上传端口，返回顶层 `packageId` 供下一条消息绑定，不再构造 `attachments[].PACKAGE_REFERENCE`。 |
| 任务进度 topic 分隔符 | 当前实现与前端均以 `/topic/task-progress-{cid}-{tid}`（连字符）为准；后端 `ConversationProgressBridge` 不改为斜杠 topic。 |
| HITL challenge 流 | 已修复：`PendingProposalRepository` 在确认边界重算 DAG / imagegen 执行事实，`ConfirmationService.submit(...)` 在创建 task 前调用 `ConfirmationTokenService.verifyAndConsume(...)`，校验 action、conversationId、packageId、payloadHash、expectedCount 与 BULK level。 |
| SSE heartbeat | 已修复：`MessageController` 按 `pixflow.conversation.sse.heartbeat-interval` 使用独立 `conversation-sse-heartbeat` 调度器发送 `: heartbeat` 注释帧，并在完成、超时、错误路径取消。 |
| SSE `tool_*` 事件 payload | 已修复：`SseAgentEventSink#value()` 支持 Map、Java record 与 JavaBean getter，`ToolCall` / `ToolExecutionResult` record payload 能投影出 `toolName/toolCallId/toolInput/content/externalized`。 |
| `assistant_message_completed` 事件 | 后端已 emit 但前端 `AgentEventName` 没声明；`messageId` 永远为空是因为 `value(String, "messageId", "")` 走 fallback。修复 `SseAgentEventSink#toPayload` 直接 `event.text()` / `String.valueOf(payload)`，并把 `messageId` 作为 metadata 键传入。 |
