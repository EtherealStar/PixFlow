# 前端传输层与 API Adapter 设计

## 定位

传输层负责把浏览器网络能力收口为稳定的前端协议基础设施。业务页面不得直接拼接 `fetch`、SSE 解析或 STOMP 连接；应通过 `src/api/*` 与 `src/transport/*` 使用统一能力。

## HTTP Client

`src/api/client.ts` 是唯一 HTTP 请求入口。它的职责是：

- 注入 `X-Trace-Id`，调用方未提供时生成新 traceId。
- 透传调用方提供的 `Idempotency-Key`，不自行生成业务幂等键。
- 对 JSON envelope 做统一解包：后端 `{ success, data }` 返回时取 `data`。
- 兼容后端分页字段：当 payload 存在 `records` 且没有 `items` 时映射为 `items`。
- 将 HTTP 错误、非 JSON 错误和网络错误归一为 `ApiError`。
- 对 `GET` 网络错误或 5xx 自动退避 500ms 后重试一次。
- 对普通业务请求返回 `401 AUTH_TOKEN_EXPIRED` 时调用认证状态机的 `refreshOnce()`，成功后重试原请求一次；登录、注册、refresh 等 `auth: false` 请求不参与该逻辑。
- 用 in-flight 信号量限制全局并发，分片上传 worker 可通过 `skipInFlight` 绕开。

**错误归一化兼容性**（来自 a14e1168 与 a5f1324a 报告）：后端 `ApiResponse` 字段是 `success/code/message/data/details/traceId`，**没有** `errorCode` 字段；前端 `client.ts` 当前是 `const errorCode = String(obj.errorCode ?? obj.code ?? 'INTERNAL_ERROR')` —— 这种双写兼容是有意为之，未来如果某天 Provider 改成 `errorCode` 也可以平滑过渡。读取方应同时支持 `code` 与 `errorCode`。

`request()` 不应承担业务级状态机职责。比如上传分片重试、HITL 提交幂等键、任务取消乐观更新，都在对应 runtime 或页面编排中处理。access token 过期刷新是传输层横切能力，但刷新动作本身必须委托给 `src/runtime/authSession.ts`，并且必须 single-flight。

## API Adapter

| 文件 | 职责 | 已知前后端不一致 |
|---|---|---|
| `api/auth.ts` | 登录、登出、用户信息与 token 管理。 | `AuthUser.id?: string` 后端不存在该字段，`UserView` 只有 `userId: number`。 |
| `api/conversations.ts` | 会话创建、列表、详情、归档。 | `ConversationView.id` 应映射为前端的 `conversationId`；列表参数 `archived=false` 实际后端是 `includeArchived=false`（参见 `chat.md` 待排查）；分页 `page=0` 会触发 400 INVALID_PARAM，需改为 `page>=1`。 |
| `api/messages.ts` | 消息历史 + 发送。 | `getHistory` 响应 TS 类型是 `{ messages, compressionMarkerCount, total }`，后端是 `PageResponse<MessageView>` `{ records, total, page, size }`。`SendMessageRequest.packageBinding: { packageId }` 应改为顶级 `packageId: string`。`sinceSeq` 不被后端接受。 |
| `api/confirm.ts` | HITL challenge 与 submit。 | `SubmitResponse` 期望 `{ taskId, status: 'PENDING'\|'RUNNING' }`，后端是 `{ proposalId, taskId, status: 'CONFIRMED' }`，`status` 是字符串而非有限枚举。 |
| `api/packages.ts` | 素材包上传、分片、包查询、素材图片列表、删除、重命名。 | `imageId` 实为 long 型，不是 string；`packageId` 后端 JSON 用 `id`。详见 `upload.md`。 |
| `api/tasks.ts` | 任务状态、会话任务、任务结果、会话产物聚合、下载、取消、删除、重命名。 | `TaskStatusView` 缺 `skipped` 与 `lastError`；`listConversationTasks` 应返回 `TaskSummary` 而不是 `TaskStatusView`；`DownloadHandle` 缺 `contentType` 与 `sizeBytes`；`CancellationResult` 缺 `taskId`。 |
| `api/tasks.ts#cancelTask` | 取消。 | 当前 TS 类型 `Promise<{ cancelled: true }>`，实际 `cancelled: boolean`。 |
| `api/downloads.ts` | 跨素材与产物的自定义 ZIP 打包下载。 | OK。 |
| `api/rubrics.ts` | Wave 6 评分占位接口。 | 待 Wave 6 实施后核对。 |
| `api/attachments.ts` | **会话附件上传**。 | **当前实现无法工作**：后端没有 `POST /api/conversations/{cid}/attachments` 端点，调用恒返 404。详见 `api.md → Attachment API`。 |

API adapter 必须保持“薄”：只负责路径、query、请求体和类型，不缓存业务数据，不 import 组件。

## SSE

Agent 回合使用 `fetch + ReadableStream` 实现 SSE，以支持自定义 header、POST body 和 AbortSignal。`runtime/useAgentTurn.ts` 通过 `createSseClient` 订阅：

事件名与 payload（来自 `SseAgentEventSink#toPayload`）：

| 事件 | Payload | 备注 |
|---|---|---|
| `assistant_delta` | `{ text, assistantCallId, modelTurnIndex, iteration, traceId, turnNo }` | 文本增量。前端应立即追加到当前 `assistant` timeline item，不等待 `completed`。 |
| `assistant_message_completed` | `{ finalText, messageId, assistantCallId, modelTurnIndex, iteration, traceId, turnNo }` | 一次模型调用的 assistant 片段完成。前端将当前 assistant item 标记为 `completed`，但整个用户回合不一定结束。若随后收到 `transition: TOOL_USE`，下一轮模型输出应创建新的 assistant item。 |
| `tool_call_ready` | `{ toolName, toolCallId, toolInput, assistantCallId, modelTurnIndex, iteration, traceId, turnNo }` | 工具调用已可展示。前端应创建或更新对应 tool timeline item，状态 `queued`。 |
| `tool_started` | `{ toolCallId, toolName?, assistantCallId, modelTurnIndex, iteration, traceId, turnNo }` | 工具开始执行。前端把对应 tool item 状态改为 `running`。 |
| `tool_result` | `{ toolCallId, toolName?, content, metadata?, externalized, error?, assistantCallId, modelTurnIndex, iteration, traceId, turnNo }` | 工具执行结果。前端把对应 tool item 改为 `completed` 或 `error`，并展示摘要；不要解析完整 stdout/stderr 来判断成功。 |
| `transition` | `{ reason, assistantCallId, modelTurnIndex, iteration, traceId, turnNo, attempt?, retriesRemaining?, errorCode?, message?, retrying? }` | 主循环 transition。`TOOL_USE` 表示会继续下一轮模型调用，不是前端终态；`RATE_LIMIT_RETRY` 表示模型调用正在由后端重试，前端保持 streaming 并显示状态行。 |
| `completed` | `{ finalText, assistantCallId?, modelTurnIndex?, iteration?, traceId, turnNo }` | 整个用户回合完成。只用于解锁输入、关闭 SSE 和标记 turn 终态，不是开始渲染 AI 消息的信号。 |
| `error` | `{ message, errorCode?, traceId? }` | 流内错误。前端保留已经渲染的 timeline，只把当前 turn 标记为 error。 |

> **heartbeat 注释帧**：后端通过 `SseHeartbeat` 定时发送 `: heartbeat\n\n` 注释帧；`transport/sse.ts` 会在收到任何字节时刷新静默计时。heartbeat 只用于连接保活，不能当成业务事件。

SSE 断流且未收到 `completed` 时，前端视为 `STREAM_INTERRUPTED`，不做 Last-Event-ID 续传。

### Agent Timeline 渲染语义

Web 不采用 CLI 的 checkpoint / static scrollback 提交机制。CLI 需要 checkpoint 是因为终端动态区和静态区会互相覆盖；Vue 前端可以直接用响应式状态更新同一个 timeline item。因此 Agent SSE 的前端消费模型是：

```text
SSE AgentEvent -> useAgentTurn reducer -> StreamingTurnState.timeline -> MessageStream 渲染
```

`MessageStore` / transcript 是模型上下文和历史恢复的事实源；运行中的 UI 事实源是 SSE event stream。页面组件不得在回合运行中从 message history 反推正在生成的 assistant 文本或工具状态。

推荐的运行中 timeline item：

```ts
type TimelineItem =
  | {
      id: string
      type: 'assistant'
      assistantCallId: string
      modelTurnIndex: number
      text: string
      status: 'streaming' | 'completed'
    }
  | {
      id: string
      type: 'tool'
      assistantCallId: string
      modelTurnIndex: number
      toolCallId: string
      toolName: string
      input?: unknown
      result?: string
      status: 'queued' | 'running' | 'completed' | 'error'
    }
  | {
      id: string
      type: 'transition'
      reason: string
      attempt?: number
      retriesRemaining?: number
      errorCode?: string
      message?: string
      retrying?: boolean
    }
```

后端事件应携带稳定归属字段：`assistantCallId`、`modelTurnIndex`、`iteration`、`toolCallId`。前端仍保留本地递增序号 fallback 以防御旧响应，但新协议以显式归属字段为准，避免多轮 tool-call 回合中把事件归到错误的 assistant 片段。

`transition.reason === 'RATE_LIMIT_RETRY'` 是非终态事件，不得触发 `setPhase('error')` 或 reject 当前 send promise。前端应在 timeline 中追加或更新一条重试状态行，保留已有 assistant/tool item；若后续收到新的 `assistant_delta` 或 `completed`，本轮按正常成功路径继续。

`MessageController.submit` 当前不读 `Idempotency-Key` header（仅 `ConfirmationController#submit` 也未读），但 `ConfirmationService` 内部已用 `proposal.proposalId` 作幂等键串行化；前端 `confirm.ts` 仍按 proposalId 维护 sessionStorage 是合理的。

## STOMP / WebSocket

任务进度使用共享 STOMP 连接，入口位于 `src/transport/ws.ts`。**实际 STOMP topic 由 `ConversationProgressBridge` 拼装：`/topic/{prefix}-{cid}-{tid}`**（前缀默认 `task-progress`，配置项 `pixflow.conversation.progress.topic-prefix`），**连字符分隔，不是斜杠**。

**已知不一致**：`runtime/useTask.ts:123` 当前订阅 `/topic/task-progress/${cid}/${tid}`（斜杠），与实现不一致（实现 `/topic/task-progress-{cid}-{tid}`）。前端订阅收不到任何帧。修复优先级：高——这是任务进度面板不工作的根因之一。

```text
/topic/task-progress-{conversationId}-{taskId}
```

### STOMP 帧 schema

```ts
// ProgressEvent（平铺字段，没有嵌套 progress）
{ taskId: string, done: number, total: number, failed: number, skipped: number, status: 'PENDING' | 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'PARTIAL', occurredAt: string }
```

> **前端不一致**：`useTask.applyWsFrame` 期望字段 `completedAt` / `cancelledAt`，**应改为 `occurredAt`**（参见 `TaskQueryServiceImpl.subscribe` 与 `ProgressEvent` record）。

WS 帧只做增量更新；重连或进入任务页时必须通过 `GET /api/tasks/{taskId}` 拉快照校准，避免本地状态落后。

WS 鉴权走 STOMP CONNECT header `Authorization: Bearer <token>`（参见 `AuthWebSocketInterceptor`），**不要**改用 query 参数——后者不支持。

## 错误与脱敏

- `ApiError.traceId` 必须一路传到 toast 或 traceId 浮层，便于排障。
- `confirmationToken`、上传文件字节和预签名 URL 不能进入 console。
- 429 的 `Retry-After` 由 HTTP client 解析到 `ApiError.retryAfterMs`，实际退避策略由上传 worker 或业务调用方执行。
- 401 只在 `AUTH_TOKEN_EXPIRED` 时自动刷新并重试一次。`AUTH_TOKEN_INVALID`、`AUTH_TOKEN_REVOKED`、`AUTH_REFRESH_INVALID` 等错误不在 HTTP client 内无限恢复，应由认证状态机清理登录态并由路由把用户带回登录页。
- 后端 `ApiResponse` 字段顺序为 `success/code/message/data/details/traceId`。**错误 envelope 中 `code` 字段就是后端业务错误码**，例如 `INVALID_PARAM`、`AUTH_TOKEN_EXPIRED`、`TASK_NOT_FOUND` 等。前端 `ApiError.errorCode` 与 `message` / `details` 都从同一 envelope 取。

## 约束

1. 新增接口先更新 `api.md` 或确认已有契约，再新增 `src/api/*` 方法。
2. 不在页面中直接调用 `fetch`；预签名 URL 下载除外，因为下载动作由浏览器直接打开 URL。
3. 不在 `transport/` 中 import store 或组件。
4. 不把 token、文件字节、预签名 URL 打印到日志。
5. `auth: false` 请求不得携带 Authorization，也不得触发 refresh 重试；这覆盖 `/api/auth/login`、`/api/auth/register`、`/api/auth/refresh`。
6. **WS topic 严格按后端约定订阅**，不要把 `useTask.ts` 的 `/topic/task-progress/${cid}/${tid}` 当作正确——后端不会在那里发帧。
