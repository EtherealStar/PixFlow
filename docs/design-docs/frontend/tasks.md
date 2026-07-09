# Tasks 前端模块设计

## 定位

Tasks 模块负责任务面板、任务详情页、任务进度订阅、取消操作、结果预览和下载。它连接 Agent HITL 提交后的 taskId、右侧任务面板和 `/tasks/:tid` 深链页面。

## 关键实现

- 页面：`src/pages/TaskDetailPage.vue`
- 状态机：`src/runtime/useTask.ts`
- Store：`src/stores/tasks.ts`、`src/stores/ui.ts`
- 组件：`components/tasks/TaskPanel.vue`、`TaskCard.vue`、`TaskProgressCard.vue`、`TaskDetailHeader.vue`、`ResultPreview.vue`
- API：`src/api/tasks.ts`

## 任务状态

前端 runtime 状态：

```text
queued | running | completed | failed | partial | cancelled
```

后端状态通过 `statusToPhase()` 映射（与 `TaskStatus` 枚举对齐）：

| 后端 | 前端 |
|---|---|
| `PENDING`、`QUEUED` | `queued` |
| `RUNNING` | `running` |
| `COMPLETED` | `completed` |
| `FAILED` | `failed` |
| `PARTIAL` | `partial` |
| `CANCELLED` | `cancelled` |

> 备注：`TaskStatus` 完整枚举值：`PENDING, QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, PARTIAL`；`terminal()` 判定为后四种。

## 数据流

1. 页面或任务卡创建 `createTask({ taskId, conversationId })`。
2. `refresh()` 拉 `GET /api/tasks/{taskId}` 作为初始快照。响应 `TaskStatusView`：顶层字段 `taskId/taskType/status/skipped/lastError/createdAt/startedAt/finishedAt`，嵌套 `progress: { done, total, failed }`。
3. `subscribeWS()` 订阅 `/topic/task-progress-{conversationId}-{taskId}`（**连字符**，参见 `transport-api.md`）。
4. WS 帧更新进度、状态与完成时间。**WS 帧字段**：`{ taskId, done, total, failed, skipped, status, occurredAt }`——全是平铺字段，没有嵌套 `progress`；也没有 `completedAt` / `cancelledAt`，统一是 `occurredAt`。
5. 进入终态后组件可以调用 `listTaskResults()` 展示结果。
6. 下载单个结果用 `getResultDownload()`；下载全任务结果用 `getBundleDownload()`。

## 取消语义

用户取消时，前端先乐观设置 `phase='cancelled'`，再调用：

```text
POST /api/conversations/{conversationId}/tasks/{taskId}/cancel
```

响应（`CancellationResult`）：`{ taskId, cancelled: boolean }`。前端 `api/tasks.ts#cancelTask` 当前类型 `Promise<{ cancelled: true }>` 过窄——`cancelled` 是 `boolean` 不是字面量 `true`，且缺少 `taskId` 字段。

后端 WS 推送真实取消帧时幂等处理，不重复提示。`useTask#cancel` 失败时当前实现保持 cancelled，并把错误写入 runtime 的 `error` ref。

## 右侧任务面板

右侧任务面板读取 `useTasksStore.items` 与 `watching` 集合，不持有 WS 连接。新任务出现时，可由页面或业务流程调用 `useUiStore.autoExpandRightPanel()` 展开 6 秒。

## 数据模型细节（与后端对齐）

`TaskStatusView` 真实字段：

| 字段 | 类型 | 前端是否使用 |
|---|---|---|
| `taskId` | string | ✓ |
| `taskType` | `IMAGE_PROCESS` \| `IMAGE_GEN` | ✓ |
| `status` | `TaskStatus` | ✓ |
| `progress.done` | int | ✓ |
| `progress.total` | int | ✓ |
| `progress.failed` | int | ✓ |
| `skipped` | int | **✗** 当前 `TaskState.progress` 未含 |
| `lastError` | string \| null | **✗** 当前 `TaskState` 未含 |
| `createdAt` / `startedAt` / `finishedAt` | Instant | ✓ |

`TaskResultView` 真实字段：`resultId, taskId, conversationId, status (ResultStatus), kind (UnitKind), imageId, skuId, groupKey, viewId, branchId, filename, displayName, size, url, createdAt, finishedAt, errorMsg`。

`ResultStatus` 枚举：`PENDING, RUNNING, SUCCESS, FAILED, SKIPPED`。
`UnitKind` 枚举：`BRANCH, GROUP, GENERATIVE`。

`TaskSummary`（会话任务列表响应）字段是**平铺**的 `done/total/failed`（不是嵌套 `progress`）。前端 `api/tasks.ts#listConversationTasks` 当前返回类型 `Page<TaskStatusView>` 错——应是 `Page<TaskSummary>`。

`DownloadHandle` 真实字段：`{ url, expiresAt, contentType, sizeBytes }`。前端当前只声明 `url`/`expiresAt`，缺 `contentType` 与 `sizeBytes`。

`PageResult<T>` 字段：`{ records, total, page (int), size (int) }`。前端 HTTP client 已自动把 `records` 重映射为 `items`，可直接读 `items`。

## 约束

1. 任务实时状态以 `useTask` runtime 为准；组件不直接处理 STOMP。
2. WS 帧必须按 taskId 过滤，避免跨任务串状态。
3. WS 帧字段必须是 `occurredAt`（不是 `completedAt`/`cancelledAt`）。
4. WS topic 必须是 `/topic/task-progress-{cid}-{tid}`（连字符分隔），否则收不到任何帧。
5. 任务结果图片仍通过后端预签名 URL 展示，不代理字节。
6. 新增任务状态时必须同步 `TaskStatus`、`statusToPhase()`、UI badge 与错误提示。
