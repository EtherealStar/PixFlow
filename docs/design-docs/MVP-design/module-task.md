# module-task —— 任务管理与结果下载

> 源码：`src/main/java/com/etherealstar/pixflow/module/task/`

任务模块是 PixFlow 的「执行与查询出口」：

- 接收来自 `ConversationController.confirm` 的请求，服务端重新校验 DAG 后创建任务并同步驱动执行引擎。
- 暴露任务列表、任务详情、结果分页查询。
- 提供结果图预览 URL、流式原始字节、流式打包下载。

## 1. 目录结构

```
module/task/
├── controller/  TaskController
├── service/     TaskService / ResultDownloadService
├── entity/      ProcessTask / ProcessResult
├── mapper/      ProcessTaskMapper / ProcessResultMapper
└── dto/         ConfirmRequest / ConfirmResponse
                 TaskListItem / TaskDetailResponse / TaskResultItem
```

## 2. 实体

### 2.1 `ProcessTask`（`process_task` 表）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `Long` | 主键，自增 |
| `conversationId` | `Long` | 来源对话 id（软关联） |
| `packageId` | `Long` | 目标素材包 id（软关联） |
| `dagJson` | `String` | DAG JSON（由 `DagJsonCodec` 从已校验 `Dag` 序列化） |
| `status` | `Integer` | 0 待执行 / 1 执行中 / 2 完成 / 3 失败 |
| `totalCount` | `Integer` | 待处理图片数 |
| `doneCount` | `Integer` | 已完成数（同步模型下一般等于 totalCount） |
| `createdAt` | `LocalDateTime` | 创建时间 |
| `finishedAt` | `LocalDateTime` | 执行结束时刻（执行中为 null） |

### 2.2 `ProcessResult`（`process_result` 表）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `Long` | 主键，自增 |
| `taskId` | `Long` | 所属任务 id（软关联） |
| `imageId` | `Long` | 对应原图 id（软关联） |
| `skuId` | `String` | SKU ID |
| `branchId` | `String` | 支路标识，同一 `imageId` 下唯一（需求 9.2） |
| `outputPath` | `String` | 处理后图片相对存储路径（失败为 null） |
| `generatedCopy` | `String` | 文案（文案分支结果，可空） |
| `status` | `Integer` | 0 待处理 / 1 成功 / 2 失败 |
| `errorMsg` | `String` | 失败原因（成功为 null，最大 1000 字符） |
| `createdAt` | `LocalDateTime` | 创建时间 |

## 3. 端点

### 3.1 `TaskController`（`/api/task`）

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/list` | 任务列表（分页 + `status` 筛选） |
| `GET` | `/{taskId}` | 任务详情（含分页结果列表） |

> 确认执行端点位于 `POST /api/conversation/{conversationId}/confirm`（见 `ConversationController`），业务实现在 `TaskService.confirm`。

### 3.2 结果相关端点（实现在 `module/file` 的 `ResultPreviewController`）

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/asset/result/list` | 结果分页列表（可选 `taskId` 筛选） |
| `GET` | `/api/asset/result/{id}/preview` | 结果图预览 URL |
| `GET` | `/api/asset/result/{id}/raw` | 流式原始字节 |
| `GET` | `/api/asset/result/download/{taskId}` | 流式 zip 打包下载 |

## 4. 服务实现

### 4.1 `TaskService`

`service/TaskService.java` 串起「确认执行 → 创建任务 → 同步执行 → 返回预览 URL」：

```
confirm(conversationId, request)
   │
   ├── requireConversation(conversationId)             ── CONVERSATION_NOT_FOUND
   │
   ├── request.packageId 缺失 → PACKAGE_UNAVAILABLE
   │
   ├── requireReadyPackage(packageId)                  ── 不存在 / status != READY → PACKAGE_UNAVAILABLE
   │                                                     details: packageId / requiredStatus / actualStatus
   │
   ├── dagValidator.validateJson(request.dagJson)      ── 任意错误码（DAG_*）抛 BusinessException
   │
   ├── imageMapper.selectList(package_id, orderByAsc("id"))   ── 全部已识别图片
   │
   ├── createTask(conversationId, packageId, dag, imageCount)
   │     ├── dagJsonCodec.write(dag)                  ── 序列化入库
   │     ├── status=0 / totalCount=imageCount / doneCount=0 / createdAt=now
   │     └── processTaskMapper.insert
   │
   ├── executionEngine.execute(task, dag, images)      ── 同步阻塞执行，返回 DagExecutionSummary
   │
   └── buildPreviewUrls(taskId)                        ── 见 §4.3
         → ConfirmResponse(taskId, summary.status, totalCount, doneCount, previewUrls)
```

`listTasks(page, size, status)`：

- `Pagination.of` 校验分页。
- `status` 必须在 `{0, 1, 2, 3}`，否则 `INVALID_TASK_STATUS`（details 含 `status / allowed`）。
- 排序：`created_at desc, id desc`（稳定次级排序）。
- 返回 `PageResponse<TaskListItem>`。

`taskDetail(taskId, resultPage, resultSize)`：

- 任务不存在 → `TASK_NOT_FOUND`。
- 结果分页：`Pagination.of` 校验；按结果 `id asc` 翻页。
- 返回 `TaskDetailResponse`（含 `dagJson`、状态、计数、`results: List<TaskResultItem>`，失败项带 `errorMsg`）。

### 4.2 合法任务状态集

```java
private static final Set<Integer> VALID_STATUS = Set.of(0, 1, 2, 3);
```

| 值 | 含义 |
|---|---|
| 0 | 待执行（创建后、执行前） |
| 1 | 执行中（MVP 同步模型下基本瞬时） |
| 2 | 完成（至少一条成功） |
| 3 | 失败（全部结果失败） |

### 4.3 预览 URL 选取

`buildPreviewUrls(taskId)`：

- 查 `process_result`：`task_id = ? AND status = 1 AND output_path IS NOT NULL`。
- 按 `imageId asc, id asc` 排序。
- 取前 `min(3, n)` 条，URL 模板 `"/api/asset/result/%d/raw"`（需求 8.6、8.7）。

### 4.4 `ResultDownloadService`

`service/ResultDownloadService.java`：

- `listResults(page, size, taskId)`：分页 + `taskId` 过滤，按 `id asc` 翻页；`PageResponse<TaskResultItem>`。
- `prepareDownloadName(taskId)`：校验任务存在、存在成功结果（`status=1` 且 `output_path` 非空）；无任何成功结果 → `NO_DOWNLOADABLE_RESULT`；返回 `task_{taskId}_results.zip`。
- `streamZip(taskId, out)`：用 `ZipOutputStream` 逐文件流式写出，缓冲区固定 8 KB（峰值内存与结果数无关，需求 13.6）；单文件失败仅 `log.warn` 并跳过，不中断整体下载。条目名 `{sanitize(skuId)}_{resultId}.{ext}`，`resultId` 全局唯一保证 zip 内互不冲突（需求 13.4）。

> 由 `module.file.controller.ResultPreviewController.download` 以 `StreamingResponseBody` 调用 `streamZip` 实现流式 HTTP 响应。

## 5. DTO

| DTO | 字段 | 用途 |
|---|---|---|
| `ConfirmRequest` | `dagJson, packageId` | 确认执行请求体 |
| `ConfirmResponse` | `taskId, status, totalCount, doneCount, resultPreviewUrls` | 同步执行结果 + 前 3 张预览 |
| `TaskListItem` | `id, conversationId, packageId, status, totalCount, doneCount, createdAt, finishedAt` | 列表项 |
| `TaskDetailResponse` | `id, conversationId, packageId, dagJson, status, totalCount, doneCount, createdAt, finishedAt, results` | 详情（含嵌套分页结果） |
| `TaskResultItem` | `id, taskId, imageId, skuId, branchId, outputPath, generatedCopy, status, errorMsg, previewUrl` | 结果项；成功时 `previewUrl = "/api/asset/result/%d/raw"`，失败时为 null |

## 6. 同步执行模型与不变量

- **同步执行**：`TaskService.confirm` 调用 `executionEngine.execute` 是**同步阻塞**的，请求返回时 `process_task.status` 已是终态（2/3），`finishedAt` 已写入，前端立即拿到 `resultPreviewUrls`。
- **DAG 不信任原则**：`dagValidator.validateJson(request.dagJson)` 在 `confirm` 中第一步走完，**任何 DAG_* 错误均不创建任务**。
- **预览数量上限**：`PREVIEW_LIMIT=3`（硬编码常量；按 `imageId asc` 取前 `min(3, n)`，与 `branch` 无关，只取「最早几张图的代表性成功结果」）。
- **打包下载前置校验**：`prepareDownloadName` 在响应头写出前完成校验，避免半响应后才发现无结果。
- **状态机一致**：任务 `status` 与 `process_result.status` 解耦，但有对应：
  - 任务 `2`（完成）⇔ 至少一条 `process_result.status=1`。
  - 任务 `3`（失败）⇔ 全部 `process_result.status=2`。
- **删除任务的素材包约束**：`PackageDeleter` 检查 `process_task.package_id` 引用，引用数 > 0 → `PACKAGE_REFERENCED_BY_TASK`。这是「任务 → 素材包」反向引用唯一硬约束。

## 7. 模块间关系

```
ConversationController.confirm
        │
        ▼
TaskService.confirm
        ├── ConversationMapper.selectById       (校验对话存在)
        ├── AssetPackageMapper.selectById      (校验素材包就绪)
        ├── DagValidator.validateJson          (DAG 严格校验)
        ├── DagJsonCodec.write                 (DAG 序列化)
        ├── ProcessTaskMapper.insert           (创建任务)
        ├── AssetImageMapper.selectList        (加载图片)
        ├── DagExecutionEngine.execute
        │     ├── BranchExpander.expand
        │     ├── ImageWorkerPool.runAll       (并发执行)
        │     ├── ImageToolExecutor.apply      (像素工具)
        │     ├── ImageCodec.encode            (落盘)
        │     ├── CopyGenerator.generate       (文案分支)
        │     ├── FailureIsolator.markFailed   (失败隔离)
        │     ├── ProcessResultMapper.insert   (逐结果入库)
        │     └── ProcessTaskMapper.updateById (任务终态)
        ├── ProcessResultMapper.selectList     (取成功结果)
        └── ConversationService.attachTask     (回填 message.task_id)
```
