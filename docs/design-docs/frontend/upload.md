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

1. `sha256File(file)` 对完整文件原始字节做流式/增量 SHA-256，计算标准 `fileHash`。`fileHash` 不得由各分片 hash 拼接后二次摘要得到。
2. `initUpload({ filename, size, fileHash, chunkSize })` 初始化。请求 body 字段名就是 `filename/size/fileHash/chunkSize`，全小写、long 型。
3. 后端返回三种模式（`InitUploadResponse`）：
   - `mode='UPLOAD'`：`{ uploadId, packageId: null, status: null, chunkSize, expectedChunks, uploadedChunks: [] }`。
   - `mode='DEDUP', status='READY'`：直接进入 `done`，回调 `onDone(packageId)`。
   - `mode='RESUME', status='UPLOADING'`：接管后端返回的 active `uploadId`，以 `uploadedChunks` 为初始完成集合，只上传差集。
4. 新上传时保存本地 session：`fileHash -> { uploadId, filename, size, chunkSize, savedAt }`。
5. `getSession(uploadId)` 拉已上传分片校准（`UploadSessionState` 9 字段：`uploadId, fileHash, size, chunkSize, expectedChunks, uploadedChunks, failedChunks, status, packageId`，**没有 `createdAt` / `updatedAt`**）。
6. `runChunkPool()` 并发上传缺失分片（`PUT /sessions/{uploadId}/chunks/{index}`）。每个实际上传分片独立计算 `chunkHash = SHA256(chunkBytes)`，通过 `X-Chunk-Hash` 发送，由后端流式复算校验。
7. `completeUpload(uploadId, fileHash?)` 完成拼接和解压。响应 `{ packageId, status: 'UPLOADED' }`。
8. 成功后删除本地 session，进入 `done`。

## Init 响应目标契约

| 模式 | uploadId | packageId | status | chunkSize | expectedChunks | uploadedChunks |
|---|---|---|---|---|---|---|
| UPLOAD | uuid | null | null | 5242880 | 20 | [] |
| DEDUP-READY | null | 123 | "READY" | 0 | 0 | [] |
| RESUME | existing-uuid | null | "UPLOADING" | 5242880 | 20 | [0,1,2,3] |

`InitUploadResponse` 必须建模为 `UPLOAD | RESUME | DEDUP` 三种互斥形态。`RESUME` 不是错误或去重命中；它必须携带有效 `uploadId/chunkSize/expectedChunks/uploadedChunks`。

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

暂停与取消必须分离：暂停只 abort 当前在飞请求并保留本地 session，不调用后端 DELETE；恢复时重新计算 `fileHash`、调用 init，消费 `RESUME + uploadedChunks`。浏览器刷新、断网和进程退出都按暂停语义处理。

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
5. `fileHash` 只用于整包身份、整包去重、同文件并发上传协调和 complete 校验；`chunkHash` 只用于单分片完整性与当前 `uploadId` 内重复 PUT 幂等。
6. “分片幂等”不表示跨上传会话的物理分片去重；不同 `uploadId` 不共享临时 chunk 对象。
7. 上传接口不应被页面直接串联，必须通过 `createUploadJob()` 管理状态。
8. **DEDUP 与 RESUME 都走成功响应**：`DEDUP` 进入 `done`，`RESUME` 进入 `uploading` 并过滤已上传分片；不要再按 `FILE_HASH_ALREADY_EXISTS` 或同 hash 冲突错误码判断。
