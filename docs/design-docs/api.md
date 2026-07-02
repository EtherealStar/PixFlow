# PixFlow 前端 API 文档

> 本文档记录后端暴露给 Vue 前端的接口，依据 `design.md`、当前执行计划，以及 `docs/design-docs/` 下各模块设计文档整理。
> 本文档有意排除 Agent 工具 schema、Java SPI、MQ consumer、内部 service API 和 provider adapter。

## 范围与约定

- 对外 HTTP 路由统一归一到 `/api` 前缀。部分模块文档中的 conversation 示例未带此前缀；本文按前端网关统一前缀处理。
- Agent 长文本输出使用 SSE。任务进度与素材包解压进度使用 WebSocket/STOMP。
- 普通 JSON 响应应遵循 `base/common.md` 定义的统一成功 / 错误 envelope；本文示例只展示业务 payload。
- 所有 HTTP/SSE/WS 帧应携带或能够关联 `traceId`。
- 真实执行确认提交请求必须带 `Idempotency-Key`，用于避免网络重试或用户重复点击导致重复创建任务。

## API 归属

| 范围 | 归属模块 | 前端接口形态 |
|---|---|---|
| 对话与 Agent 回合 | `module/conversation` | REST + SSE |
| HITL 确认 | `module/conversation` + `permission` + `task` | REST |
| 用户附件 | `module/conversation` | REST multipart |
| 素材包 | `module/file` | REST + WS 进度 |
| 任务状态 / 结果 / 下载 | `module/task` 经 `module/conversation` 或 app controller 暴露 | REST + WS 进度 |
| Rubrics 报告 | `module/rubrics` | REST |
| Trace 回放 | `harness/eval` | rubrics / debug 内部读 API；当前尚未定义公开前端 REST 路由 |

## Conversation API

### 会话 CRUD

| 方法 | 路径 | 用途 | 响应 payload |
|---|---|---|---|
| `POST` | `/api/conversations` | 创建会话。 | `{ conversationId, title, createdAt }` |
| `GET` | `/api/conversations?archived=false&page=0&size=20` | 查询会话列表。 | `{ items: [{ conversationId, title, updatedAt, packageId? }], total }` |
| `GET` | `/api/conversations/{conversationId}` | 查询会话元信息。 | `{ conversationId, title, createdAt, updatedAt, packageId? }` |
| `DELETE` | `/api/conversations/{conversationId}` | 软删除 / 归档会话。 | `204 No Content` |

### 发送消息并流式接收 Agent 回合

`POST /api/conversations/{conversationId}/messages`

请求支持 JSON 或 multipart：

```json
{
  "prompt": "帮我把这批商品图统一成白底",
  "attachments": [
    { "type": "PACKAGE_REFERENCE", "packageId": 123 }
  ],
  "packageBinding": { "packageId": 123 }
}
```

响应：`200 text/event-stream`。

SSE 事件名：

| 事件 | Payload |
|---|---|
| `assistant_delta` | `{ "text": "..." }` |
| `tool_call_ready` | `{ "toolName": "...", "toolCallId": "...", "toolInput": {...} }` |
| `tool_started` | `{ "toolCallId": "..." }` |
| `tool_result` | `{ "toolCallId": "...", "content": "...", "externalized": false }` |
| `transition` | `{ "reason": "TOOL_USE" | "REACTIVE_COMPACT_RETRY" | "COMPLETED" | "..." }` |
| `completed` | `{ "finalText": "...", "traceId": "...", "turnNo": 42 }` |
| `error` | `{ "errorCode": "...", "message": "...", "traceId": "..." }` |

SSE 说明：

- V1 不支持 `Last-Event-ID` 重连。
- 每 30 秒发送一条 heartbeat 注释帧。
- 任务进度不走该 SSE 流；前端应订阅下文 WebSocket 进度频道。

### 消息历史

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/conversations/{conversationId}/messages?page=0&size=50&sinceSeq=` | 读取用于展示的完整 transcript 时间线。 |

历史视图只读。`message` 表写入仍由 `harness/session` 负责。

## Attachment API

### 上传会话附件

`POST /api/conversations/{conversationId}/attachments`

Content-Type：`multipart/form-data`

字段：

| 字段 | 说明 |
|---|---|
| `file` | 图片二进制。 |
| `metadata` | JSON 元数据，例如 `{ "filename": "...", "contentType": "image/png" }`。 |

响应：

```json
{
  "attachmentId": "...",
  "sourceRef": "packages/.../images/...",
  "type": "UPLOAD_IMAGE"
}
```

V1 支持的附件类型：

| 类型 | 前端提供方式 |
|---|---|
| `UPLOAD_IMAGE` | 通过 `/attachments` 上传，再在消息中引用 `attachmentId`。 |
| `PACKAGE_REFERENCE` | 在 `POST /messages` 中传 `{ "type": "PACKAGE_REFERENCE", "packageId": 123 }`。 |

## HITL 确认 API

这些端点是前端触发真实图片处理或生图执行的唯一入口。Agent 工具只创建待确认提案。

### 获取二次确认 challenge 或直接 token

`POST /api/conversations/{conversationId}/confirm/{proposalId}/challenge`

**该接口被设计为可复用**：
- 第一次调用：请求体 `{}`，响应 `needChallenge=true` + `challenge` 对象（或 `needChallenge=false` + token）。
- 第二次调用（仅 `needChallenge=true` 时）：请求体 `{ "answer": "用户回答文本" }`，响应 `token` 对象或错误 `PROPOSAL_CHALLENGE_FAILED`。

请求体（第一次，无 answer）：

```json
{}
```

响应（`needChallenge=true`）：

```json
{
  "needChallenge": true,
  "challenge": {
    "challengeId": "...",
    "prompt": "该组实际仅 2 张：确认按 2 张处理，还是漏传图片？"
  }
}
```

请求体（第二次，带 answer）：

```json
{
  "answer": "按实际处理"
}
```

响应（`needChallenge=false` 或第二次 answer 通过）：

```json
{
  "needChallenge": false,
  "token": {
    "token": "...",
    "expiresAt": "..."
  }
}
```

### 提交确认

`POST /api/conversations/{conversationId}/confirm/{proposalId}/submit`

Header：

| Header | 必填 | 用途 |
|---|---|---|
| `Idempotency-Key` | 是 | 防止重复创建任务。 |

请求体：

```json
{
  "challengeAnswer": "按实际处理"
}
```

响应：

```json
{
  "taskId": "...",
  "status": "PENDING"
}
```

重要错误码：

| HTTP | 错误码 | 含义 |
|---|---|---|
| `400` | `PROPOSAL_CHALLENGE_FAILED` | challenge 答案未通过。 |
| `410` | `PROPOSAL_CHALLENGE_EXPIRED` | challenge 已过期。 |
| `401` | `CONFIRMATION_TOKEN_INVALID` | token 无效、过期或被重复使用。 |
| `404` | `PROPOSAL_NOT_FOUND` | 提案不存在。 |
| `409` | `PROPOSAL_ALREADY_CONFIRMED` | 提案已确认。 |

## Task API

任务创建不作为通用前端端点直接暴露；任务由确认提交触发。查询、取消和下载面向前端。

### 取消任务

`POST /api/conversations/{conversationId}/tasks/{taskId}/cancel`

响应：

```json
{ "cancelled": true }
```

取消是协作式的。已经开始执行的工作单元不会被中途打断；worker 在单元边界检查取消标志。

### 任务查询与结果 API

task 设计中暴露 `TaskQueryService`，用于状态查询、会话任务历史与下载。`task.md` 尚未完全固定 controller 路径；下表是应实现的规范化前端路由。

| 方法 | 路径 | 用途 | 后端能力 |
|---|---|---|---|
| `GET` | `/api/tasks/{taskId}` | 轮询任务状态。 | `TaskQueryService.getStatus` |
| `GET` | `/api/conversations/{conversationId}/tasks?page=0&size=20` | 查询会话任务历史面板。 | `TaskQueryService.listByConversation` |
| `GET` | `/api/tasks/{taskId}/results?page=0&size=50` | 查询任务结果列表，用于预览。 | `process_result` read model |
| `GET` | `/api/tasks/{taskId}/downloads?resultId=...` | 获取单个结果的预签名下载 URL。 | `TaskQueryService.getResultDownload` |
| `GET` | `/api/tasks/{taskId}/downloads/bundle` | 构建 / 获取批量打包下载 URL。 | `DownloadBundleBuilder` |

任务状态 payload 至少应包含：

```json
{
  "taskId": "...",
  "status": "PENDING | QUEUED | RUNNING | COMPLETED | FAILED | CANCELLED | PARTIAL",
  "taskType": "IMAGE_PROCESS | IMAGE_GEN",
  "progress": { "done": 320, "total": 800, "failed": 3 },
  "createdAt": "...",
  "startedAt": "...",
  "finishedAt": "..."
}
```

下载响应返回 handle，不返回文件字节：

```json
{
  "url": "https://minio-presigned-url",
  "expiresAt": "..."
}
```

应用层不得代理结果图片字节；前端预览 / 下载应直接使用预签名 URL。

## WebSocket / STOMP API

### 连接

`WS /ws/progress`

### 任务进度频道

订阅：

`/topic/task-progress/{conversationId}/{taskId}`

推送帧：

```json
{ "taskId": "...", "done": 320, "total": 800, "status": "RUNNING", "failed": 3 }
```

```json
{ "taskId": "...", "status": "COMPLETED", "completedAt": "..." }
```

```json
{ "taskId": "...", "status": "CANCELLED", "cancelledAt": "..." }
```

说明：`module/task.md` 也提到逻辑订阅 `/task/{taskId}/progress`。前端传输层应统一使用上面的 STOMP endpoint 与 topic；task 只发布 `ProgressEvent`，不拥有 WebSocket 连接。

### 素材包进度频道

订阅：

`/topic/packages/{packageId}/progress`

推送帧基于 `ExtractionProgress`：

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
| `GET` | `/api/files/packages/{packageId}` | 查询素材包详情与解压进度，作为轮询兜底。 |
| `GET` | `/api/files/packages?page=0&size=20` | 查询素材包列表。 |
| `DELETE` | `/api/files/packages/{packageId}` | 删除素材包：未被引用则物理删除，否则软删除。 |
| `GET` | `/api/files/packages/{packageId}/errors?page=0&size=50` | 查询导入 / 解压错误明细。 |

### 分片上传协议

#### 1. 初始化

`POST /api/files/packages/init`

请求体：

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
| `size` | 是 | 整包字节数。 |
| `fileHash` | 是 | 整包 SHA-256（hex）。用于整包去重 + 续传定位。 |
| `chunkSize` | 是 | 客户端声明的分片大小。V1 固定 `5242880`（5MB）。后端**必须**校验与 `pixflow.upload.chunk-size`（配置项）一致，不一致返 `400 CHUNK_SIZE_MISMATCH`；协议刚性，不允许协商。 |

init 阶段错误码：

| HTTP | 错误码 | 含义 |
|---|---|---|
| `400` | `CHUNK_SIZE_MISMATCH` | 客户端 `chunkSize` 与服务端配置不一致（V1 固定 5MB）。 |
| `400` | `FILE_HASH_INVALID` | `fileHash` 不是合法的 hex 字符串 / 长度不对。 |
| `400` | `FILE_SIZE_OUT_OF_RANGE` | `size` 超出 `pixflow.upload.max-zip-size` 上限。 |
| `429` | `UPLOAD_RATE_LIMITED` | 服务端令牌桶拒绝；按 `Retry-After` 退避。 |

响应（**新建会话**）：

```json
{
  "mode": "UPLOAD",
  "uploadId": "uuid-v4-string",
  "chunkSize": 5242880,
  "expectedChunks": 20,
  "uploadedChunks": []
}
```

响应（**整包去重命中**）：

```json
{
  "mode": "DEDUP",
  "packageId": 123,
  "status": "READY"
}
```

前端在 `mode=DEDUP` 时直接停止上传、提示"该压缩包已存在"，不进入分片上传阶段。

响应（**同 fileHash 正在被其他客户端上传**）：

```json
{
  "mode": "DEDUP",
  "packageId": null,
  "status": "UPLOADING",
  "uploadId": "existing-uuid",
  "uploadedChunks": [0, 1, 2, 3]
}
```

此时前端可选择：(a) 直接把 `existing-uuid` 作为自己的 `uploadId` 续传（被同 fileHash 互斥锁保护，安全）；(b) 提示"另一上传正在进行"让用户取消再试。

> **同 fileHash 互斥锁**：后端在 `init` 阶段取 `Redisson 锁 lock:package-upload:{fileHash}`（看门狗续期，TTL 与 `session.ttl` 对齐），校验"同 fileHash 是否已有未完结 `upload_session`"。如有则进入 `DEDUP/UPLOADING` 分支；锁内创建 `upload_session` 后释放。后续 `chunks/*` 与 `complete` 不再持该锁，避免长时间阻塞。

#### 2. 拉取会话（断点续传用）

`GET /api/files/packages/sessions/{uploadId}`

响应：

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

#### 3. 上传分片

`PUT /api/files/packages/sessions/{uploadId}/chunks/{index}`

| Header | 必填 | 说明 |
|---|---|---|
| `Content-Type` | 是 | `application/octet-stream`。 |
| `Content-Length` | 是 | 分片字节数。 |
| `X-Chunk-Hash` | 是 | 分片 SHA-256（hex）。后端校验，不一致返 4xx。 |
| `X-Chunk-Size` | 是 | 分片字节数（与 body 长度一致）。 |

Body：分片原始字节。

响应（**成功**）：

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
| `429` | `UPLOAD_RATE_LIMITED` | 触发令牌桶限流，前端按响应头 `Retry-After` 退避。 |

> **分片去重 / 锁**：`PUT /chunks/{index}` 内部取 `Redisson 锁 lock:package-chunk:{uploadId}:{index}`（短时锁，毫秒级，**不**启看门狗），校验 `chunk:{uploadId}:{index}` 是否已存在；不存在则写 MinIO 临时桶 + Redis 元数据，**MinIO 上传使用 `CopyObject` 或 `PutObject` 走单写语义**（依赖 MinIO 的 `If-None-Match: *` 实现单写）；存在则比对 hash，一致返 `ALREADY_EXISTS`，不一致返 `CHUNK_HASH_MISMATCH` 提示重传。

#### 4. 完成上传

`POST /api/files/packages/sessions/{uploadId}/complete`

请求体（**可选**校验字段，不传则跳过对应校验）：

```json
{
  "fileHash": "sha256-hex-of-whole-file"
}
```

响应（**成功**）：

```json
{
  "packageId": 123,
  "status": "UPLOADED"
}
```

响应（**分片不齐**）：

```json
{
  "errorCode": "INCOMPLETE_CHUNKS",
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
  "errorCode": "FILE_HASH_MISMATCH"
}
```

> **complete 锁**：`POST /complete` 取 `Redisson 锁 lock:package-complete:{uploadId}`（短时锁，毫秒级，**不**启看门狗）。锁内做：(1) 校验分片总数 == `expectedChunks`；(2) 校验整包 hash（如果客户端传入）；(3) 调 MinIO `composeObject` 拼接分片为完整 zip 到 `packages/{packageId}/source.zip`；(4) 校验拼接结果大小与 hash；(5) 落 `asset_package(status=UPLOADED)` + `package_upload_session.status=READY`；(6) 发 RocketMQ 解压消息；(7) 删 MinIO 临时桶 `tmp-uploads/{uploadId}/*`；(8) 释放锁。

#### 5. 取消上传

`DELETE /api/files/packages/sessions/{uploadId}`

幂等。对不存在的 `uploadId` 也返 200。

响应：

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
| `zip` | 是 | 源图片 zip。 |
| `doc` | 否 | CSV/XLSX 文案文档。 |

响应：

```json
{
  "packageId": 123,
  "status": "UPLOADED | EXTRACTING"
}
```

**V1 推荐统一走分片协议**；此端点保留用于：(1) 极小文件场景的快速通道；(2) 其他客户端（CLI、脚本）兼容性。

### 素材包详情响应

```json
{
  "packageId": 123,
  "name": "...",
  "status": "UPLOADED | EXTRACTING | READY | PARTIAL | FAILED",
  "imageCount": 300,
  "extractedCount": 120,
  "errorSummary": "..."
}
```

### 去重 / 锁 / 限流总览

| 维度 | 机制 | 责任方 |
|---|---|---|
| **整包去重** | `asset_package.file_hash` 唯一索引 + init 阶段查重 | 后端 |
| **同 fileHash 上传互斥** | `Redisson 锁 lock:package-upload:{fileHash}`（看门狗续期） | 后端 |
| **分片去重（幂等）** | `chunk:{uploadId}:{index}` Redis 元数据 + MinIO 单写语义 | 后端 |
| **分片并发写保护** | `Redisson 锁 lock:package-chunk:{uploadId}:{index}`（短时锁，毫秒级） | 后端 |
| **complete 防并发拼接** | `Redisson 锁 lock:package-complete:{uploadId}`（短时锁） | 后端 |
| **cancel 防并发清理** | `Redisson 锁 lock:package-cancel:{uploadId}`（短时锁） | 后端 |
| **上传速率限制** | 令牌桶（按 `uploadId` + 客户端 IP 二级限流），后端 429 + `Retry-After` | 后端 |
| **客户端限流** | 前端令牌桶（每秒请求数可配） | 前端 |

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

| 错误码 | HTTP | 阶段 | 含义 |
|---|---|---|---|
| `CHUNK_SIZE_MISMATCH` | 400 | init / chunk PUT | 客户端 `chunkSize` 与服务端配置不一致；或分片 body 长度与 `X-Chunk-Size` 不一致 |
| `CHUNK_HASH_MISMATCH` | 400 | chunk PUT | 分片 hash 与 body 不一致 |
| `CHUNK_OUT_OF_RANGE` | 400 | chunk PUT | `index` 超出 `expectedChunks` 范围 |
| `FILE_HASH_INVALID` | 400 | init | `fileHash` 不是合法 hex / 长度不对 |
| `FILE_HASH_MISMATCH` | 400 | complete | 拼接结果 hash 与客户端传入不一致 |
| `FILE_SIZE_OUT_OF_RANGE` | 400 | init | `size` 超出上限 |
| `INCOMPLETE_CHUNKS` | 400 | complete | 分片总数不齐 |
| `UPLOAD_SESSION_NOT_FOUND` | 404 | 任何 session 接口 | `uploadId` 不存在或已 TTL 过期 |
| `UPLOAD_SESSION_NOT_UPLOADING` | 409 | chunk PUT | session 已是 READY / CANCELLED / EXPIRED |
| `UPLOAD_RATE_LIMITED` | 429 | 任何上传端点 | 服务端令牌桶拒绝 |
| `FILE_HASH_ALREADY_EXISTS` | 409 | init | 同 fileHash 已有 READY/PARTIAL 包（响应里同时给出 `packageId`，前端视为 `DEDUP/READY`） |

## Rubrics API

`module/rubrics` 属于 Wave 6，明确提供前端 / PM 自助接口。

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
| 任务查询 / 下载 | 确认 `TaskQueryService.getStatus`、`listByConversation`、结果列表和下载 handle 的具体 REST controller 路径。 |
| Eval trace 回放 | 判断是否需要前端 debug console；若需要，应单独设计脱敏后的 trace 查询 / 回放端点。 |
