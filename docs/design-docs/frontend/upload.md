# Upload 前端模块设计

## 定位

Upload 模块负责素材包上传，包括整包 hash、初始化、分片上传、断点续传、worker 池、自动退避、取消和完成。上传模块服务于首页上传区、Files 页面上传区和左栏上传进度。

## 关键实现

- 状态机：`src/upload/uploadJob.ts`
- 分片：`src/upload/chunker.ts`
- Hash：`src/upload/hasher.ts`
- 本地续传元数据：`src/upload/sessionStore.ts`
- worker 池：`src/upload/chunkWorker.ts`
- 取消：`src/upload/cancel.ts`
- 组件：`components/upload/UploadDropzone.vue`、`PackageUploader.vue`、`UploadJobCard.vue`、`PackageExtractionProgress.vue`
- API：`api/packages.ts`

## 上传状态机

```text
idle
  -> hashing
  -> initing
  -> uploading
  -> completing
  -> done
  -> error
  -> cancelled
```

## 流程

1. `sha256File(file)` 计算 fileHash。
2. `initUpload({ filename, size, fileHash, chunkSize })` 初始化。请求 body 字段名就是 `filename/size/fileHash/chunkSize`，全小写、long 型。
3. 后端返回三种模式（`InitUploadResponse`）：
   - `mode='UPLOAD'`：`{ uploadId, packageId: null, status: null, chunkSize, expectedChunks, uploadedChunks: [] }`。
   - `mode='DEDUP', status='READY'`：直接进入 `done`，回调 `onDone(packageId)`。
   - `mode='DEDUP', status='UPLOADING'`：进入 error，由上层提示同 hash 上传冲突。
4. 新上传时保存本地 session：`fileHash -> { uploadId, filename, size, chunkSize, savedAt }`。
5. `getSession(uploadId)` 拉已上传分片校准（`UploadSessionState` 9 字段：`uploadId, fileHash, size, chunkSize, expectedChunks, uploadedChunks, failedChunks, status, packageId`，**没有 `createdAt` / `updatedAt`**）。
6. `runChunkPool()` 并发上传缺失分片（`PUT /sessions/{uploadId}/chunks/{index}`）。
7. `completeUpload(uploadId, fileHash?)` 完成拼接和解压。响应 `{ packageId, status: 'UPLOADED' }`。
8. 成功后删除本地 session，进入 `done`。

## Init / DEDUP 响应真实字段（来自 `InitUploadResponse.java:5-23`）

| 模式 | uploadId | packageId | status | chunkSize | expectedChunks | uploadedChunks |
|---|---|---|---|---|---|---|
| UPLOAD | uuid | null | null | 5242880 | 20 | [] |
| DEDUP-READY | null | 123 | "READY" | 0 | 0 | [] |
| DEDUP-UPLOADING | existing-uuid | null | "UPLOADING" | 0 | 0 | [0,1,2,3] |

> **前端不一致**（a60b9f5d 报告）：前端 `types/upload.ts#InitUploadResponse` 把 DEDUP 两种状态的 `chunkSize/expectedChunks/uploadedChunks` 字段都列了但 `DEDUP-READY` 实际上这些值是 0；`DEDUP-UPLOADING` 的 `uploadedChunks` 是 `[0,1,2,3]`。当前 TS 类型是 union 三种形态，结构上 OK，但需要让 TS 在 DEDUP 路径上不强制读取这些字段。

## Worker 池

`runChunkPool()` 使用固定并发，默认 4。每个 worker 负责：

- 读取分片 Blob。
- 计算分片 hash。
- 调 `PUT /sessions/{uploadId}/chunks/{index}`，header：`Content-Type: application/octet-stream`、`X-Chunk-Hash`、`X-Chunk-Size`。
- 根据响应中的 uploadedChunks 校准进度。
- 对网络错误、5xx、可恢复上传状态错误执行退避重试。

`CHUNK_HASH_MISMATCH`、`CHUNK_SIZE_MISMATCH`、`CHUNK_OUT_OF_RANGE`、`UPLOAD_SESSION_NOT_FOUND`、`UPLOAD_SESSION_NOT_UPLOADING` 等客户端或协议错误不自动重试。

> **没有令牌桶限流**：后端 `UPLOAD_RATE_LIMITED` 不在 `FileErrorCode` 中。前端 chunkWorker 现有 429 退避分支是无用分支，但保留也无害。

## 续传

本地只保存 uploadId 等小型元数据，不保存分片字节。刷新后通过 fileHash 找回 uploadId，再由后端 `getSession()` 返回 uploadedChunks，前端用源文件重新 slice 缺失分片。

`UploadSessionState.failedChunks` 字段**永远为空列表**（后端 `UploadSessionService.toState` 固定 `List.of()`），前端不应依赖该字段判断重传。

## 取消

取消动作必须：

1. `AbortController.abort()` 中断在飞请求。
2. 调 `DELETE /api/files/packages/sessions/{uploadId}` 通知后端清理。
3. 删除本地 session。
4. UI 立即进入 `cancelled`。

DELETE 失败不阻断 UI 取消；错误只作为 breadcrumb 或 toast 展示。

## 整文件上传兼容端点

`POST /api/files/packages`（multipart）：

- 字段：`zip` (required), `doc` (optional)
- 响应（`UploadPackageResponse`）：`{ packageId, status: 'UPLOADED' | 'EXTRACTING' | 'READY' | 'PARTIAL' | 'FAILED', messageConfirmed: boolean }`
- 注意 `status` 是 `PackageStatus` 枚举，不是字符串；多一个 `messageConfirmed` 字段。

## 错误码（来自 `FileErrorCode`，HTTP 由 `ErrorCategory.defaultHttpStatus()` 推导）

| 错误码 | HTTP | 含义 | 旧文档中是否有 |
|---|---|---|---|
| `PACKAGE_NOT_FOUND` | 404 | packageId 不存在 | — |
| `INVALID_ZIP` | 400 | 上传 zip 解析失败 | — |
| `ZIP_BOMB_DETECTED` / `ZIP_PATH_TRAVERSAL` | 409 | 安全校验失败 | — |
| `NO_VALID_IMAGE` | 409 | zip 内无有效图片 | — |
| `UNSUPPORTED_IMAGE_FORMAT` | 400 | 不支持的图片格式 | — |
| `COPY_DOC_PARSE_FAILED` | 400 | CSV/XLSX 文案解析失败 | — |
| `UPLOAD_TOO_LARGE` | 400 | size 超出上限（**替代旧 `FILE_SIZE_OUT_OF_RANGE`**） | **改名** |
| `FILE_HASH_MISMATCH` | 400 | 整包 hash 不一致或 hash 格式非法（**替代旧 `FILE_HASH_INVALID`**） | **改名** |
| `CHUNK_HASH_MISMATCH` | 400 | 分片 hash 与 body 不一致 | ✓ |
| `CHUNK_SIZE_MISMATCH` | 400 | 客户端 chunkSize 不一致 / 分片 body 长度与 X-Chunk-Size 不一致 | ✓ |
| `CHUNK_OUT_OF_RANGE` | 400 | index 超出 expectedChunks | ✓ |
| `UPLOAD_SESSION_NOT_FOUND` | 404 | uploadId 不存在或已失效 | ✓ |
| `UPLOAD_SESSION_NOT_UPLOADING` | 409 | session 已 READY / CANCELLED / EXPIRED | ✓ |
| `INCOMPLETE_CHUNKS` | 400 | 分片不齐。**注意**：后端不在 response.details 里返回 `{expected, uploaded, missing}`，只在 message 文本中带 `missing` 列表 | ✓（细节不一致） |
| `PACKAGE_DEDUP_CONFLICT` | 409 | 同 fileHash 已有正在上传的会话（**替代旧 `FILE_HASH_ALREADY_EXISTS`**） | **改名** |
| `PACKAGE_ALREADY_REFERENCED` | 409 | 删除 package 时被引用 | — |
| `ASSET_IMAGE_NOT_FOUND` | 404 | imageId 不存在 | — |
| `ASSET_IMAGE_NAME_INVALID` | 400 | 重命名 displayName 非法 | — |
| `MESSAGE_PUBLISH_FAILED` | 503 | 发解压消息失败 | — |

**已删除的旧错误码**（前端 chunkWorker 不应再依赖）：`FILE_HASH_INVALID` / `FILE_SIZE_OUT_OF_RANGE` / `FILE_HASH_ALREADY_EXISTS` / `UPLOAD_RATE_LIMITED`。

## 约束

1. 前端不实现分布式锁；同 fileHash 互斥由后端负责。
2. 前端不缓存分片字节到 IndexedDB。
3. 分片 PUT 跳过 HTTP client 全局 in-flight 限制，改由 worker 池控制并发。
4. 完成上传后必须清理本地 session，避免陈旧 uploadId 影响后续上传。
5. 上传 API 不应被页面直接串联，必须通过 `createUploadJob()` 管理状态。
6. **DEDUP 命中不再走错误码**：后端用 `mode=DEDUP/READY` 200 响应表达"已存在"，前端切到 `done` 状态。**不要**再按 `FILE_HASH_ALREADY_EXISTS` (409) 等错误码判断。
