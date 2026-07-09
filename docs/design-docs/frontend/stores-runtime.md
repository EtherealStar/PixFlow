# 前端 Store 与 Runtime 状态机设计

## 定位

Store 层保存跨组件共享的前端状态副本；Runtime 层承载长流程状态机。二者边界必须清晰：状态机负责流程推进、重试、取消和订阅；Pinia 负责让页面和组件读取当前摘要状态。

## Pinia Store

| Store | 职责 | 关键状态 | 已知前后端不一致 |
|---|---|---|---|
| `auth.ts` | 认证 runtime 的 Pinia 代理，供页面和路由读取登录态 | `token`、`user`、`phase`、`isAuthenticated` | — |
| `conversations.ts` | 会话列表、当前会话 | `items`、`currentId`、`current`、`loading` | `refresh()` 当前传 `page: 0`，需改为 `page: 1` 或省略（参见 `chat.md` 待排查问题记录）。`items` 实际从后端 `ConversationView.records` 派生，字段名是 `id`/`title`/`packageId`/`archived`/`createdAt`/`updatedAt`——前端 `ConversationSummary.conversationId` 需在 adapter 层映射或后端做 `@JsonProperty("conversationId")`。 |
| `agentTurns.ts` | Agent 回合摘要和最近 traceId | `Map<conversationId, AgentTurnSummary>`、`lastTraceId` | `AgentTurnSummary.taskId` 字段存在；`ChatPage.vue:95` 读取 `state.lastTaskId` 是错的——字段名是 `taskId`。 |
| `tasks.ts` | 任务摘要和订阅集合 | `Map<taskId, TaskState>`、`watching` | `TaskState.progress` 缺 `skipped`；`TaskState` 缺 `lastError`。 |
| `packages.ts` | 素材包摘要 | `Map<packageId, PackageDetail>` | `PackageDetail.packageId` 后端字段是 `id`；`name` 字段前端未声明。 |
| `uploadJobs.ts` | 上传任务摘要 | `Map<jobId, UploadJobState>` | OK |
| `fileIndex.ts` | 左栏与 @ 提及索引 | 由 packages + conversations 派生 | OK |
| `ui.ts` | 面板、网络、traceId | `leftPanelPinned`、`rightPanelExpanded`、`floatingTraceId` | OK |
| `toast.ts` | 全局 toast 队列 | `items` | OK |

`fileIndex.ts` 是派生 store：上传段来自 `usePackagesStore.items`，结果段和历史段来自 `useConversationsStore.items`，不重复保存后端原始数据。

## Agent 回合状态机

实现位置：`src/runtime/useAgentTurn.ts`。

状态：

```text
idle -> sending -> streaming -> completed
                              -> awaiting_challenge -> awaiting_confirm -> completed
                              -> error
                              -> cancelled
```

关键设计：

- 运行中的 Agent UI 不再只有 `deltas: ref<string>`。`useAgentTurn` 应维护一个响应式 `StreamingTurnState.timeline`，由 SSE reducer 直接更新 assistant 文本、工具状态、transition 和错误状态。
- 同一 conversationId 不允许两个 active 回合；新回合会 abort 前一回合。
- `confirmationToken` 只保存在闭包内，不暴露到 store、组件 props 或日志。
- `confirm()` 负责 challenge / answer / submit 三步编排。
- `Idempotency-Key` 按 proposalId 存入 `sessionStorage`，重复提交复用同一 key。
- `reject()` 不调用后端 `/submit`，只更新本地 proposal 状态，并让页面插入“已拒绝”本地消息。
- Pinia 的 `agentTurns.ts` 只保存回合摘要（phase、traceId、taskId、error 等）和最近 traceId；完整 timeline 是运行中 UI 状态，不进入 Pinia，也不作为 transcript 事实源。

### Agent Streaming Timeline

Web 端采用“直接 SSE reducer + 响应式 timeline”的渲染模型，不采用 CLI 的 checkpoint / scrollback coordinator。CLI 的 checkpoint 是为了解决终端动态区与静态区输出冲突；Vue 可以直接更新同一个 timeline item，所以事件处理应保持简单：

```text
createSseClient
  -> onEvent
  -> reduceAgentEvent(state, event)
  -> state.timeline
  -> MessageStream
```

推荐状态结构：

```ts
interface StreamingTurnState {
  phase: AgentTurnPhase
  timeline: TimelineItem[]
  activeAssistantId?: string
  activeTools: Record<string, string> // toolCallId -> timeline item id
  turnCompleted: boolean
  error?: ApiError
}
```

`TimelineItem` 至少覆盖：

- `assistant`：字段包含 `assistantCallId`、`modelTurnIndex`、`text`、`status: streaming | completed`。
- `tool`：字段包含 `assistantCallId`、`modelTurnIndex`、`toolCallId`、`toolName`、`input`、`result`、`status: queued | running | completed | error`。
- `transition`：只在需要调试或产品上希望显示“继续思考 / 使用工具”时渲染；默认可以只影响状态行。
- `error`：流内错误保留前面已渲染内容，只把当前 turn 标记为 error。

事件归约规则：

- `assistant_delta`：没有 active assistant item 时创建一个；把 `text` 追加到该 item。
- `assistant_message_completed`：把 active assistant item 标记为 `completed`；若 payload 带 `finalText` 且本地 text 为空，可用它补齐。
- `tool_call_ready`：创建或更新 tool item，状态 `queued`。
- `tool_started`：更新 tool item 为 `running`。
- `tool_result`：更新 tool item 为 `completed` 或 `error`，展示结果摘要；不要靠解析 stdout/stderr 推断状态。
- `transition: TOOL_USE`：保持 turn 在 `streaming`，下一轮 `assistant_delta` 创建新的 assistant item。
- `completed`：只标记整个 turn 完成、关闭 SSE、解锁输入；不是开始渲染 AI 消息的触发点。

归属字段要求：后端 SSE 事件应携带 `assistantCallId`、`modelTurnIndex`、`iteration` 和 `toolCallId`，前端 reducer 用这些字段把事件归属到对应 timeline item。短期兼容时可以用本地递增序号兜底，但不能把这种 fallback 写成长期协议。

> **已知缺陷**（a0ca73f3 报告）：
>
> 1. `setProposal` 当前只由测试注入 `__setPendingProposal` 调用——SSE 流中后端如果发出"proposal"，**前端没有 handler 把 proposal 写入 `proposals`/`pending`**，导致 `confirm(proposalId)` 总是抛 `PROPOSAL_NOT_FOUND`。需要在 `onEvent` 中识别后端"提案事件"（V1 后端通过 SSE 直接发出 `completed`/`tool_*` 等事件，**没有专门的 proposal 事件**——proposal 由后端在 state 中维护，前端通过 challenge 接口拉取；当前端在收到 `awaiting_challenge` 状态机后再主动调 `challenge()` 即可。`setProposal` 仅用于测试）。
> 2. `doSubmit` 当前调用 `submitConfirm(_, _, { challengeAnswer: undefined }, idemp)` **没有把 `pending.challengeId` 传给后端**。`ConfirmationSubmitRequest` 实际字段是 `challengeId` / `challengeAnswer`，后端 `ConfirmationService.verifyChallenge` 要求 challengeId 非空。需要把 body 改成 `{ challengeId: pending.challengeId, challengeAnswer }`。
> 3. 后端 `ConfirmationChallengeResponse.token` 实际是 `ConfirmationToken.tokenId`（String），**没有 `expiresAt` 字段**。前端 `ConfirmationToken.token` / `expiresAt` 类型是错的，需要改为 `token: string`。
> 4. 后端 challenge 流**实际上只有 challenge 与 submit 两步**：`/challenge` 不接受 body，`/submit` 才接受 `challengeAnswer`。前端 `confirm()` 当前实现是"先 challenge 空 body，再 challenge 带 answer"——第二次 challenge 调用永远拿到同一个 challenge，不会拿到 token。**正确做法**是 `confirm(proposalId, answer)` 内部：
>    - 第一次进入时只调 `/challenge`（无 body）拿 challenge。
>    - 用户在 ChallengeDialog 输入答案后再次调用 `confirm(proposalId, answer)`，**直接**走 `/submit`，body `{ challengeId, challengeAnswer: answer }`。
> 5. 已修复：`useAgentTurn` 对外暴露 `timeline`，`assistant_delta` / `assistant_message_completed` / `tool_call_ready` / `tool_started` / `tool_result` 均进入 SSE reducer；`MessageStream` 直接渲染 timeline，不再依赖单个 `deltas` 文本缓冲。
> 6. `ConfirmSubmitResponse` 后端是 `{ proposalId, taskId, status: 'CONFIRMED' }`，前端 `SubmitResponse` 期望 `{ taskId, status: 'PENDING' | 'RUNNING' }`——`proposalId` 字段被丢、`status` 类型不对。
> 7. `ChatPage.vue:169` 从 `turn?.proposals.value[0]` 取 proposal 是错误的（proposals 是 append-only，会取到旧的 proposal）。当前页面没有维护"当前 awaiting proposal"指针——应在 `useAgentTurn.confirm()` 入口把 proposalId 作为参数传入。

当前页面通过 `watch(turn.state)` 将摘要同步到 `useAgentTurnsStore`，从而更新 traceId 浮层。

## 认证状态机

实现位置：`src/runtime/authSession.ts`。认证状态机管理 access token、当前用户、启动恢复、登录、注册、登出和 token 过期刷新。Pinia 的 `src/stores/auth.ts` 只代理状态机暴露的响应式状态和方法，不自己发起 `/api/auth/refresh`。

状态：

```text
anonymous -> bootstrapping -> authenticated
anonymous -> bootstrapping -> anonymous
authenticated -> expired
authenticated -> anonymous
```

关键设计：

- `bootstrap()` 只用于应用启动或直接刷新受保护路由时的一次恢复。它先用本地 access token 调 `/api/auth/me`，失败后再尝试 `/api/auth/refresh`。
- 登录页和路由页签切换不调用 `bootstrap()` 或 `refresh()`；未登录时直接展示注册优先登录页。
- `login()` 和 `register()` 成功后直接写入 token 和 user，并递增内部 generation。generation 是版本号，用来防止旧的 bootstrap / refresh 失败结果覆盖新的登录成功状态。
- `refreshOnce()` 只由 HTTP client 在业务请求返回 `401 AUTH_TOKEN_EXPIRED` 时调用，同一时间只允许一个 refresh 请求在飞。
- `logout()` 和 `clear()` 会清空 token、user，并释放 STOMP 单例连接，防止旧连接继续携带旧 token。

> **已知事实**（a14e1168 报告）：
> - **refresh token 完全由后端管理**，通过 `Set-Cookie: PIXFLOW_REFRESH=...` 下发，**前端不应也不能读**。`refresh()` 调用是 `POST /api/auth/refresh` 不带 body，cookie 自动带上。
> - access token 存 localStorage（`pixflow.auth.token`），TTL 默认 15 分钟；过期由 HTTP client 自动 refresh 重试。
> - 登录失败限流 `AUTH_TOO_MANY_ATTEMPTS` (429)：5 次失败 / 10 分钟，封禁 10 分钟；按 username 与 IP（`X-Forwarded-For` 优先）独立计数。前端应当在 toast 中区分 429（"稍后再试"）与 401（"用户名或密码错误"）。

## 任务运行时

实现位置：`src/runtime/useTask.ts`。

状态：

```text
queued -> running -> completed | failed | partial | cancelled
```

关键设计：

- `refresh()` 调 `GET /api/tasks/{taskId}` 拉完整快照。
- `subscribeWS()` 订阅 **`/topic/task-progress-{conversationId}-{taskId}`**（**连字符分隔**，参见 `transport-api.md` 与 `chat.md` 待排查）。当前代码 `/topic/task-progress/${cid}/${tid}` 是错的。
- WS 帧按 taskId 过滤，只更新匹配任务。
- WS 帧字段 `taskId / done / total / failed / skipped / status / occurredAt` 都是平铺的；`status` 是 `TaskStatus` 枚举字符串。
- 后端快照通过 `createdAt/startedAt/finishedAt` 计算 `updatedAt`，旧快照不覆盖新状态。
- `cancel()` 先乐观置为 `cancelled`，再调用后端取消接口；后端取消帧幂等处理。

> **已知缺陷**：
> - WS 帧字段 `occurredAt` 当前没被 `applyWsFrame` 识别（代码读 `completedAt`/`cancelledAt`，应改为 `occurredAt`）。
> - `TaskStatusView.skipped` 与 `TaskStatusView.lastError` 没被前端 `TaskState` 持久化；后续如果要在 UI 展示跳过数 / 错误信息需要补。

## 上传状态机

实现位置：`src/upload/uploadJob.ts`。

状态：

```text
idle -> hashing -> initing -> uploading -> completing -> done
                                             -> error
                                             -> cancelled
```

关键设计：

- `sha256File()` 先计算整包 hash。
- `initUpload()` 决定新上传、已存在去重或同 hash 上传冲突。
- 新上传会把 `fileHash -> uploadId` 写入 `sessionStore`，用于刷新后续传定位。
- `runChunkPool()` 用固定 worker 池上传分片，worker 自行处理 5xx、429 与可重试错误。
- `cancel()` abort 在飞请求，调用 `DELETE /sessions/{uploadId}`，并清理本地 session。
- `retry()` 只在非运行中状态重置并重新 run。

> **已知事实**：
> - 后端 `UPLOAD_RATE_LIMITED` 错误码**不存在**；上传链路不会被 429 限流。前端 `chunkWorker` 现有 429 退避分支是无用分支，但不会触发，可以保留无害。
> - `INCOMPLETE_CHUNKS` 错误后端不返回 `details: { expected, uploaded, missing }`，只在 `message` 字符串里包含 `missing` 列表。前端如果要做精细化 UI（"还差哪些分片"）需要从 message 文本解析，或后端 `UploadSessionService.complete` 改用 `BusinessException(FileErrorCode.INCOMPLETE_CHUNKS, msg, Map.of(...))` 把结构化 details 带过去。
> - `UploadSessionState.failedChunks` 永远为空列表（后端 `UploadSessionService.toState` 固定 `List.of()`），前端不应依赖该字段。

## 约束

1. Runtime 可以调用 API 和 transport；store 不应直接管理长连接或分片 worker。
2. 页面组件只组合 runtime、store 和视觉组件，不重写流程状态机。
3. 流式 token、分片上传进度、WS 帧处理都必须在 runtime 或 upload 模块中维护。
4. Store 不做大对象持久化；上传续传只保存 uploadId 等小型元数据。
5. Auth store 不直接编排 refresh；认证恢复与 token 续期必须通过 `runtime/authSession.ts` 完成。
