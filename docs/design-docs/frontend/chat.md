# Chat 前端模块设计

## 定位

Chat 模块负责 `/chat/:cid` 会话体验：用户输入、@ 提及、流式 Agent 回复、工具中间态、HITL 提案确认、二次确认弹窗和任务进度卡片挂载。

## 关键实现

- 页面：`src/pages/ChatPage.vue`
- 状态机：`src/runtime/useAgentTurn.ts`
- Store：`src/stores/conversations.ts`、`src/stores/agentTurns.ts`、`src/stores/toast.ts`
- 组件：`components/chat/MessageStream.vue`、`Composer.vue`、`MentionPopover.vue`、`ProposalCard.vue`、`ChallengeDialog.vue`
- 任务卡片：`components/tasks/TaskProgressCard.vue`

## 页面流程

1. 路由进入 `/chat/new` 时，页面调用 `conversations.create()` 创建会话，再跳转到真实 `/chat/{conversationId}`。
2. 路由带 `?q=` 时，页面在会话就绪后自动发送该 prompt，并清理 query。
3. 用户通过 `Composer` 发送文本；页面把用户消息先写入本地 `userMessages`，再调用 `turn.send(text)`。
4. `useAgentTurn` 通过 SSE reducer 维护运行中 `timeline`；`MessageStream` 渲染用户消息和 `turn.timeline`，而不是只渲染一个 `turn.deltas` 文本泡泡。
5. 当 `turn.proposals` 出现 proposal 时，页面渲染 `ProposalCard`，用户可确认或拒绝。
6. 确认 proposal 后，`turn.confirm()` 编排 challenge 与 submit；拿到 taskId 后插入 `TaskProgressCard`。

## Agent 消息流渲染

Chat 页面应参考 `docs/references/cli-message-rendering-architecture.md` 的核心边界，但不要照搬 CLI 的 checkpoint / static scrollback 提交机制。CLI 需要 checkpoint 是因为终端动态区和静态 scrollback 会冲突；Vue 前端可以直接用响应式 timeline 更新同一个节点。

Web 端的运行中渲染模型：

```text
SSE AgentEvent
  -> runtime/useAgentTurn.reduceAgentEvent
  -> StreamingTurnState.timeline
  -> MessageStream
```

`MessageStream` 不应以 `messages.map(renderMessage)` 重绘完整后端 transcript，也不应只展示 `deltas`。它应渲染一条按事件顺序增长的 timeline：

- 用户本地消息：由页面在发送时插入，用于即时回显。
- assistant item：`assistant_delta` 到达时创建或追加文本；`assistant_message_completed` 到达时标记 completed。
- tool item：`tool_call_ready` 创建 queued 工具卡；`tool_started` 改为 running；`tool_result` 填入结果并标记 completed/error。
- transition item：`RATE_LIMIT_RETRY` 必须渲染成简短状态行（例如“模型连接中断，正在重试”），并保持回合 phase 为 `streaming`；其他 transition 默认不必渲染成消息，可用于状态行或调试。
- error item：流内错误保留已渲染内容，只追加错误提示或更新 turn 状态。

`completed` 只表示整个用户回合结束，用于解锁输入和关闭 SSE，不是开始显示 AI 消息的信号。多轮工具调用场景下，一个用户消息后面可以出现多条 assistant item 与 tool item：

```text
user
assistant(streaming -> completed)
tool(queued -> running -> completed)
assistant(streaming -> completed)
completed(turn terminal)
```

timeline 是运行中 UI 状态，不是模型上下文事实源。模型上下文、恢复和历史展示仍以后端 `MessageStore` / transcript / history API 为准。回合完成后可以按需刷新历史，但刷新不能覆盖仍在运行的 timeline。

## HITL 设计

`ProposalCard` 是真实副作用执行的 UI 守门点。确认动作必须走（**根据后端实际实现**）：

```text
challenge (空 body) -> [可选] 用户输入答案 -> submit 带 { challengeId, challengeAnswer } + Idempotency-Key
```

> **修正**：旧版文档说"challenge 第二次调用带 answer"，但后端 `ConfirmationController#challenge` 不接受 `@RequestBody`；只有 `/submit` 端点接受 `challengeAnswer`。前端 `useAgentTurn.confirm(proposalId, answer)` 应在第一次进入时调 `/challenge`（无 body），拿到 `challengeId` 后写入闭包；用户在 ChallengeDialog 输入答案后再次调用 `confirm(proposalId, answer)`，**直接**走 `/submit`，body 是 `{ challengeId, challengeAnswer: answer }`。

错误处理：

- `PROPOSAL_CHALLENGE_FAILED`：保持 challenge 输入，允许用户重答。**注意**：`/submit` 时未带 challengeId / challengeId 失效也会抛此错误。
- `PROPOSAL_CHALLENGE_EXPIRED`：进入 error，提示用户重新生成方案。
- `PROPOSAL_ALREADY_CONFIRMED`：按幂等成功处理；**后端响应 `details.taskId` 不存在**——`ConfirmationSubmitResponse` 是 `{ proposalId, taskId, status: 'CONFIRMED' }`，需要单独把 `taskId` 字段写到 `state.taskId`。
- `CONFIRMATION_TOKEN_INVALID`：进入 error。

拒绝 proposal 不调用后端 submit；页面插入本地“已拒绝方案”消息，避免污染远端 transcript。

> **后端 HITL 实现警告**（a5f1324a 报告）：`ConfirmationService#submit` 实际**不调用** `verifyAndConsume`——`payloadHash` / `expectedCount` / `BULK level` 都没有校验。`PendingProposal.from` 把 `expectedCount` 硬编码 0，导致 `requiresChallenge` 永远为 false，challenge 流程实际上不会触发（`/challenge` 直接发 token 不进 challenge 状态机）。前端能拿到的"token"目前只是字符串占位符，不是真实门禁。

## @ 提及

`Composer` 的 @ 提及数据来自 `useFileIndexStore.search(query)`。搜索只在本地派生索引中进行，不发请求。候选覆盖上传素材包与结果会话节点。

## Composer 组件 props 实际定义

`components/chat/Composer.vue:15-19` 当前声明：

```ts
defineProps<{
  modelValue: string
  sending?: boolean
  streaming?: boolean
}>()
```

**不一致点**：`pages/ChatPage.vue:181` 当前传 `:busy="sending" :streaming="..."`，但 `Composer` 实际 prop 是 `sending` 而非 `busy`——`:busy` 是无效 prop、被静默忽略，导致 `canSend` 在回合进行中始终为 `true`，可重复点击发送按钮。修复：把 `:busy="sending"` 改为 `:sending="sending"`（或彻底删掉 `:busy`，依赖 `:streaming` 控制 stop 按钮可见性）。

## 约束

1. `confirmationToken` 不得作为组件 props、store 字段或 console 输出。
2. 运行中 timeline 不进入 Pinia；Pinia 只保存回合摘要、traceId、taskId 与错误状态。
3. 同一会话同时只能有一个 active Agent 回合。
4. Chat 页面只负责组合 UI 与 runtime，不直接解析 SSE；SSE 事件只能在 `useAgentTurn` reducer 中归约。
5. 新增 Agent 事件类型时，应先更新 `types/agent.ts`、`useAgentTurn` reducer 和 `MessageStream` timeline 渲染，再更新视觉组件。
6. 工具调用过程必须可见；`tool_call_ready` / `tool_started` / `tool_result` 不能只写 `console.debug`。

## 待排查问题记录（2026-07-06）

登录后进入 Chat 页面时出现一组前端运行时错误，当前现象集中在会话初始化、路由参数和空值保护：

- `Composer.vue:97`：`Cannot read properties of undefined (reading 'trim')`，说明输入组件中存在对可能为 `undefined` 的文本值直接调用 `.trim()` 的路径。根因：`canSend` 取 `props.modelValue.trim()` 但 `props.modelValue` 可能为空字符串而非 undefined——可加空值保护；更深层的根因是 `ChatPage.vue` 没有传 `:modelValue`，`Composer` 走的是 `v-model` 但当前页面用 `@send` 事件，导致父组件没绑 v-model。需要按父组件实际事件流重构。
- `ChatPage.vue:169`：`Cannot read properties of undefined (reading '0')`，说明页面模板或渲染逻辑中存在对未就绪数组/列表直接读取首项的路径。根因：`turn?.proposals.value[0] ?? null` 在 `proposals.value` 为空数组时不会爆，但 `proposals.value[0]` 在 undefined 时会。当前 `proposals` 是 `ref<Proposal[]>([])`，理论上不会是 undefined——但若 `turn?.proposals.value` 是 `undefined`（turn 为 null 时），`undefined[0]` 就会爆。需要 `(turn?.proposals.value ?? [])[0]` 写法。
- `GET /api/conversations/undefined 404`：Chat 页面把 `undefined` 当作会话 ID 请求详情，最终返回 `CONVERSATION_NOT_FOUND`。根因：`ChatPage.vue` 把 `cid = route.params.cid as string` 直接传给 `select()`，没有判断 `'new'`。新会话创建后 `await conversations.create()` 同步赋值 `currentId.value = c.conversationId`，但 `select` 调用可能发生在 create 之前。
- 浏览器地址出现 `/chat/undefined`：会话创建或跳转流程没有拿到有效 `conversationId`，但仍执行了路由替换。根因：同上。
- `GET /api/conversations?archived=false&page=0&size=50 400`：历史会话列表请求使用 `page=0`，后端返回 `INVALID_PARAM` / `分页参数非法`，说明前后端分页基准或参数约束不一致。
  - **真根因**：后端 `Pagination.of(page, size)` 强制 `page >= 1`，`page=0` 抛 `INVALID_PARAM` (400)。前端 `stores/conversations.ts:15` 写死 `page: 0`——必须改为 `page: 1` 或省略让后端默认。
  - **附带**：前端发的 URL 参数名是 `archived=false`，后端真实参数名是 `includeArchived=false`。后端 `@RequestParam(defaultValue = "false") boolean includeArchived` 会忽略未知参数 `archived`，因此 `archived` 这个 key 不报错，只是无效。前端应改为 `includeArchived`。
- **`useAgentTurn.confirm()` 二阶段调用**：第二次 `confirm(proposalId, answer)` 调 `/challenge` 带 `{ answer }` 是错的——`/challenge` 不接受 body。正确做法是直接走 `/submit` 带 `{ challengeId, challengeAnswer: answer }`。
- **`ChatPage.vue:95` 读 `state.lastTaskId`**：`AgentTurnSummary` 实际字段名是 `taskId`，导致任务卡片挂载逻辑失效。

### Chat 初始化健壮性修复清单

1. `stores/conversations.ts:15`：`page: 0` → `page: 1`；`archived: false` → `includeArchived: false`（或保留 `archived` 单纯作为兼容参数）。
2. `pages/ChatPage.vue`：所有 `turn?.proposals.value[0] ?? null` 改为 `(turn?.proposals.value ?? [])[0] ?? null`；所有 `state.lastTaskId` 改为 `state.taskId`。
3. `pages/ChatPage.vue:181`：`:busy="sending"` → `:sending="sending"`（与 `Composer` 实际 prop 对齐）。
4. `components/chat/Composer.vue:97`：`canSend` 加 `(props.modelValue ?? '').trim().length > 0 && !props.sending`。
5. `runtime/useAgentTurn.ts#confirm`：第一次进入调 `/challenge`（空 body）拿 challenge；用户输入答案后再次调用 `confirm(proposalId, answer)` 直接走 `/submit` 带 `{ challengeId, challengeAnswer: answer }`。
6. `runtime/useAgentTurn.ts#doSubmit`：body 增加 `challengeId: pending.challengeId`。
7. `types/agent.ts`：`AgentEventName` 增加 `assistant_message_completed`；新增 timeline item 类型；`ConfirmationToken` 字段 `token: string`（删除 `expiresAt`）；`SubmitResponse` 改为 `{ proposalId, taskId, status: string }`。
8. `runtime/useAgentTurn.ts`：用 `StreamingTurnState.timeline` 替代单个 `deltas` 字符串；`tool_*` 事件进入 timeline，不能只 debug。
9. `components/chat/MessageStream.vue`：从 `deltas` prop 改为 `timeline` prop，支持 assistant item 和 tool item 的响应式更新。
