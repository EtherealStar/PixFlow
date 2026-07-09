# 修复前后端不一致中的前端部分

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划按仓库根目录的 `PLANS.md` 维护。本文是自包含的中文执行计划：后续执行者只需要当前工作树和本文，就能理解为什么要做、哪些问题属于前端修复范围、应按什么顺序修改、如何验证行为。执行本计划前仍应按 `AGENTS.md` 先阅读 `docs/design-docs/exec-plans/` 中当前执行计划、`docs/design-docs/index.md`，并对照 `docs/design-docs/frontend/` 中的模块设计文档。

## Purpose / Big Picture

当前 PixFlow Web 与后端真实协议存在一组已经确认的不一致，直接影响用户进入 Chat、发送 Agent 回合、确认 HITL 提案、查看任务进度、浏览历史消息、上传或引用素材包。完成本计划后，前端不再向后端发送已知错误的路径、query 参数、分页参数和请求字段；前端 TypeScript 类型与 adapter 会显式映射后端真实字段；Chat 和 Task runtime 能按后端真实 SSE / STOMP payload 推进状态；不再调用不存在的会话附件上传端点。

可观察结果是：进入 `/chat/new?q=...` 能创建真实会话并发送一次消息；会话列表请求不再因为 `page=0` 返回 400；Composer 在发送或流式输出时不能重复点击叠加回合；HITL challenge 路径会在 `/submit` 时带上 `challengeId`；任务进度卡订阅 `/topic/task-progress-{conversationId}-{taskId}` 后能收到帧并用 `occurredAt` 更新完成时间；前端类型检查和相关单元测试能覆盖这些契约。

## Progress

- [x] (2026-07-06 22:30+08:00) 阅读 `PLANS.md`、`docs/design-docs/index.md`、当前活跃执行计划，以及用户提供的“最终前后端不一致清单”。
- [x] (2026-07-06 22:40+08:00) 阅读前端设计文档 `docs/design-docs/frontend/README.md`、`transport-api.md`、`stores-runtime.md`、`chat.md`、`tasks.md`、`upload.md`、`files.md`、`api.md`、`shell-routing-auth.md`。
- [x] (2026-07-06 22:50+08:00) 检查当前前端源码，确认 `useTask.ts`、`useAgentTurn.ts`、`ChatPage.vue`、`api/conversations.ts`、`api/messages.ts`、`api/confirm.ts`、`api/tasks.ts`、`api/attachments.ts`、`types/agent.ts` 等文件仍有报告中列出的旧契约。
- [x] (2026-07-06 23:05+08:00) 新建本执行计划，限定为“前端部分修复”，并记录实现机制、里程碑、验证方式和外部依赖。
- [x] (2026-07-06) 修复直接造成线上 bug 的前端项：A1、A2、A3、A4、A5、A6、A8、A9、A10、A11。
- [x] (2026-07-06) 修复数据模型与 adapter 的前端项：B1、B2、B3、B5、B6、B7、B8、B9、B10、B11、B12、B13、B15、B16。
- [x] (2026-07-06) 收敛上传错误码与无效分支：C1、C2、C3、C4、C5、C6、C8 中前端可处理部分。
- [x] (2026-07-06) 修复协议行为层前端项：D1、D5、D7、D8、D10、D15 中前端可处理部分。
- [x] (2026-07-06) 补充前端 adapter/runtime/store 单元测试和页面级回归测试。
- [x] (2026-07-06) 运行 `pnpm --dir pixflow-web typecheck` 和 `pnpm --dir pixflow-web test`，两者均通过。

## Surprises & Discoveries

- Observation: 当前已有活跃计划 `docs/design-docs/exec-plans/frontend-backend-contract-alignment-plan.md` 覆盖了后端、前端、上传、SSE、WS 的全链路对齐，并且记录为部分已实施。本计划不能重写那份全链路计划，而是聚焦用户这次报告中仍需前端落地或兜底的项。
  Evidence: 该计划的 `Outcomes & Retrospective` 记录后端 controller、SSE、WS、分片上传等已实施；但当前 `pixflow-web/src/runtime/useTask.ts` 仍订阅斜杠 topic，`pixflow-web/src/api/conversations.ts` 仍发送 `archived` 和 `page=0`。

- Observation: 前端文档已经把很多不一致写成“已知缺陷”，但源码尚未全部修复。
  Evidence: `docs/design-docs/frontend/transport-api.md` 明确 WS topic 是 `/topic/task-progress-{cid}-{tid}`，而 `pixflow-web/src/runtime/useTask.ts` 仍拼 `/topic/task-progress/${cid}/${tid}`。

- Observation: 用户报告中的 A7 属于后端 SSE 投影 bug，前端只能避免把空 payload 当成正常内容，不能在前端恢复丢失的 `toolName/toolCallId/toolInput/content/externalized`。
  Evidence: 报告指出根因是 `SseAgentEventSink#value()` 只识别 Map，不识别 Java record；前端收到的数据已经为空。

- Observation: `api/attachments.ts#uploadAttachment` 调用的 `POST /api/conversations/{cid}/attachments` 在后端不存在。前端正确修复不是让附件入口报错，而是把这个 adapter 改成普通文件上传调用的包装层：底层复用 `api/packages.ts` 的素材包上传端口或上传状态机，成功后返回可用于 `POST /messages` 的 `PACKAGE_REFERENCE` 和顶层 `packageId`。
  Evidence: `docs/design-docs/frontend/api.md` 的 Attachment API 明确“后端没有这个 endpoint”；用户报告 A8 也列为恒 404。

- Observation: 部分分页约定在当前文档中存在历史矛盾。前端修复以当前后端真实 controller 与报告为准：会话列表必须 `page >= 1`，会话列表参数名是 `includeArchived`；task 查询若 controller 使用自己的 `PageQuery` 可接受 `page=0`，则前端不要把会话分页规则机械套到 task controller。
  Evidence: `docs/design-docs/frontend/files.md` 对列表接口提示 `page` 从 1 开始，但 `docs/design-docs/frontend/api.md` 又指出 task 模块 `PageQuery` 接受 `page=0`。本计划要求每个 adapter 按对应后端 controller 单独测试。

## Decision Log

- Decision: 前端修复采用“adapter normalize + runtime consume normalized model”的机制，不让页面组件直接兼容后端字段差异。
  Rationale: 路径、query、字段名和响应形状属于 API adapter 边界；ChatPage、FilesPage、TaskProgressCard 只应消费稳定前端模型。这样能减少同一个后端字段在多个页面重复映射。
  Date/Author: 2026-07-06 / Codex

- Decision: 本计划只修前端可控部分；后端真实缺陷在计划中记录为外部依赖，不在前端伪造数据填平。
  Rationale: 例如 SSE `tool_*` payload 已在后端被投影为空，前端无法凭空恢复工具名和输入；伪造会让排障更困难。
  Date/Author: 2026-07-06 / Codex

- Decision: 保留 `api/attachments.ts#uploadAttachment` 作为前端兼容调用入口，但其内部必须走普通文件/素材包上传端口，不能调用 `/api/conversations/{cid}/attachments`，也不能直接抛“附件上传不可用”。
  Rationale: 后端没有 conversation 私有附件端点，`MessageController.submit` 是 JSON-only；但用户仍需要 Composer 附件入口可用。把附件入口包装到普通文件上传链路，可以复用既有分片、整文件上传、去重、进度和素材包事实源，上传完成后再以 `PACKAGE_REFERENCE` 绑定到消息。
  Date/Author: 2026-07-06 / Codex

- Decision: 若附件入口接收的是普通文件或 zip，优先复用现有素材包上传状态机；如仅需保留简单 adapter，`uploadAttachment()` 可直接调用普通文件上传 API 并把响应 normalize 为 `{ packageId, attachment: { type: 'PACKAGE_REFERENCE', packageId } }`。
  Rationale: 计划的重点是前端不再访问不存在路径，同时附件上传仍然能完成。具体使用分片上传还是整文件兼容端点由当前 UI 能力和文件大小策略决定，但所有路径都必须落到 file 模块已有上传端口。
  Date/Author: 2026-07-06 / Codex

- Decision: HITL challenge 前端流程固定为 `/challenge` 无 body，若需要用户答案则第二次直接 `/submit`，body 带 `{ challengeId, challengeAnswer }`。
  Rationale: 后端 `ConfirmationController#challenge` 不读 body，`ConfirmationService.verifyChallenge` 在 `/submit` 阶段要求 `challengeId` 非空。前端第二次调用 `/challenge` 带 answer 是无效流程。
  Date/Author: 2026-07-06 / Codex

- Decision: 任务 WS 以当前后端 topic `/topic/task-progress-{conversationId}-{taskId}` 为准，不推动后端改成斜杠路径。
  Rationale: 报告和前端文档都指出后端由 `ConversationProgressBridge` 拼接连字符 topic；改前端成本更低，且不破坏已有发布者。
  Date/Author: 2026-07-06 / Codex

## Outcomes & Retrospective

2026-07-06 / Codex: 完成前端契约修复。会话列表 adapter 改为 `includeArchived` 与 `page=1`，并把后端 `id` 归一化为 `conversationId`；Chat 页面修复 `taskId`、Composer `v-model` / `sending`、proposal 空值保护和任务卡挂载；HITL 改为 challenge 空 body、submit 带 `challengeId/challengeAnswer`；SSE 增加 `assistant_message_completed`，error payload 只要求 `message`；任务 WS topic 改为连字符，进度帧使用 `occurredAt/skipped`；消息历史改为分页响应并解析 JSON 字符串 metadata；会话附件上传 adapter 已复用普通素材包上传端口 `/api/files/packages`，成功后返回可用于消息发送的 `PACKAGE_REFERENCE`，Composer 拖拽/选择文件会先上传为素材包并在下一条消息中带上顶层 `packageId` 与附件引用；素材包 adapter 映射 `id -> packageId` 并校验图片路径 ID；AuthUser 删除不存在的 `id`；上传错误码移除旧 `FILE_*` 和 `UPLOAD_RATE_LIMITED` 主分支。新增契约测试覆盖会话 query、历史消息、任务 WS topic/frame、HITL challenge/submit、附件上传复用素材包端口，并更新错误码与 auth 测试。验证结果：`pnpm --dir pixflow-web typecheck` 通过；`pnpm --dir pixflow-web test` 通过（18 个测试文件，81 个测试）。

## Context and Orientation

PixFlow 前端位于 `pixflow-web/`，使用 Vue 3、Pinia、Vue Router、fetch + ReadableStream SSE、STOMP WebSocket 和 Vitest。前端分层约定是：`src/api/` 只封装 HTTP 路径、query、请求体和响应类型；`src/transport/` 只封装 SSE / STOMP / token 等传输能力；`src/runtime/` 管理 Agent 回合、任务订阅、上传等长流程状态机；页面组件组合 runtime、store 和 UI，不直接解析后端协议。

与本计划直接相关的文件如下。

`pixflow-web/src/api/conversations.ts` 负责会话创建、列表、详情和归档。当前 `listConversations` 参数是 `{ archived?: boolean; page?: number; size?: number }`，会发 `archived=false`；后端真实参数是 `includeArchived=false`。`stores/conversations.ts` 当前传 `page: 0`，会触发后端 `Pagination.of()` 的 `INVALID_PARAM`。

`pixflow-web/src/api/messages.ts` 负责消息历史类型和非流式消息请求类型。当前 `SendMessageRequest` 使用 `packageBinding: { packageId: number }`，后端真实字段是顶层 `packageId: string`。当前 `getHistory` 类型是 `{ messages, compressionMarkerCount, total }`，后端真实响应是 `PageResponse<MessageView>`，字段为 `records/total/page/size`，前端 HTTP client 会把 `records` 映射到 `items`。

`pixflow-web/src/runtime/useAgentTurn.ts` 负责 Agent 回合 SSE 和 HITL。当前 `confirm()` 第二次会继续调用 `/challenge` 并把答案塞进 body；当前 `doSubmit()` 没有传 `challengeId`；当前 `sseEventDataSchemas.error` 要求 `errorCode` 和 `traceId`，但后端 SSE `error` 只有 `{ message }`；当前没有处理 `assistant_message_completed`。

`pixflow-web/src/pages/ChatPage.vue` 负责 `/chat/:cid` 页面。当前 `onSend()` 读取 `state.lastTaskId`，但 `AgentTurnSummary` 字段是 `taskId`；当前给 `Composer` 传 `:busy`，但 `Composer.vue` prop 是 `sending` / `streaming`；当前 `ChallengeDialog` 取 `turn?.proposals.value[0]`，应避免 turn 或 proposals 未就绪时读 undefined。

`pixflow-web/src/runtime/useTask.ts` 负责任务状态和 WS 订阅。当前订阅 `/topic/task-progress/${cid}/${tid}`，后端真实 topic 是 `/topic/task-progress-{cid}-{tid}`；当前 WS frame 类型读 `completedAt/cancelledAt`，后端真实字段是 `occurredAt`，同时包含 `skipped`。

`pixflow-web/src/api/tasks.ts` 负责任务查询、结果、下载和取消。当前 `TaskStatusView` 缺 `skipped` 和 `lastError`；`listConversationTasks` 返回类型写成 `Page<TaskStatusView>`，但后端 `TaskSummary` 是平铺 `done/total/failed`；`DownloadHandle` 缺 `contentType` 和 `sizeBytes`；`cancelTask` 类型写成 `{ cancelled: true }`，后端是 `{ taskId, cancelled: boolean }`。

`pixflow-web/src/api/packages.ts` 和 `pixflow-web/src/types/upload.ts` 负责素材包和素材图片。后端 `AssetPackage` JSON 主键是 `id`，前端使用 `packageId`；计划要求在 adapter 层 normalize 为 `packageId`，不要让页面同时认两套字段。`AssetImageView.imageId` 后端 JSON 是 long 转字符串，但路径参数 controller 是 long；前端可以保留 string 作为展示模型，调用路径时要明确把数值字符串 encode，不要把任意非数字字符串传入。

`pixflow-web/src/api/attachments.ts` 目前调用不存在的 `/api/conversations/{cid}/attachments`。本计划要求保留这个前端入口但重写其内部实现：它必须调用普通文件/素材包上传端口，例如复用 `api/packages.ts` 的分片上传状态机或整文件上传兼容端点，上传成功后返回 `packageId` 与 `PACKAGE_REFERENCE` 附件输入；Composer 拖文件或附件入口随后在 `POST /messages` 中引用该 `packageId`。

## Plan of Work

第一阶段先修直接造成线上 bug 的最小前端契约。编辑 `stores/conversations.ts` 和 `api/conversations.ts`，把会话列表默认请求改为 `includeArchived=false&page=1&size=50`，并在 adapter 中把后端 `ConversationView.id` normalize 为 `conversationId`。同一阶段编辑 `ChatPage.vue`，把 `state.lastTaskId` 改为 `state.taskId`，把 `:busy="sending"` 改为 `:sending="sending"`，并把 `ChallengeDialog` 的 proposal 选择改成 `(turn?.proposals.value ?? [])[0] ?? null` 或更好的当前 pending proposal 指针。这样可以立刻消除会话列表 400、任务卡片挂不上和 Composer 重复点击。

第二阶段修任务 runtime。编辑 `runtime/useTask.ts`、`stores/tasks.ts` 和 `api/tasks.ts`。`subscribeWS()` 的 destination 必须拼成 `/topic/task-progress-${encodeURIComponent(conversationId)}-${encodeURIComponent(taskId)}`。`applyWsFrame()` 的输入类型改为 `{ taskId, done?, total?, failed?, skipped?, status?, occurredAt? }`，用 `occurredAt` 更新 `finishedAt` 和 `updatedAt`；`TaskState.progress` 增加 `skipped`，`TaskState` 增加 `lastError`。`applyBackend()` 从 `TaskStatusView.skipped` 和 `TaskStatusView.lastError` 写入 state。`cancelTask()` 类型改为 `Promise<{ taskId: string; cancelled: boolean }>`。这个阶段的验收是任务卡订阅真实 topic 后能收到帧，且取消、完成帧都能落到同一状态机。

第三阶段修 HITL 确认流程。编辑 `api/confirm.ts`，让 `challenge()` 不再接受 answer body，或者保留兼容签名但内部始终发送空 body；`SubmitResponse` 改成 `{ proposalId: string; taskId: string; status: string }`；`submit()` 的 body 类型改成 `{ challengeId?: string; challengeAnswer?: string }`。编辑 `types/agent.ts`，把 `ConfirmationToken` 改成只有 `token: string`，删除 `expiresAt`；`ChallengeOrToken.token` 对应后端字符串时可用 normalize，把后端 `"uuid"` 转为 `{ token: "uuid" }` 或直接改类型为 `string` 并在 runtime 中处理。编辑 `useAgentTurn.ts`：第一次 `confirm(proposalId)` 调 `/challenge`；如果 `needChallenge=true`，保存 `pending.challengeId` 并返回空 taskId；第二次 `confirm(proposalId, answer)` 不再调 `/challenge`，直接进入 `doSubmit(proposalId, answer)`，提交 `{ challengeId: pending.challengeId, challengeAnswer: answer }`。若 `needChallenge=false`，保存 token 后直接 submit，但仍不把 token 写到 store 或日志。

第四阶段修 SSE 事件与错误类型。编辑 `types/agent.ts` 增加 `assistant_message_completed`，新增 payload `{ finalText: string; messageId?: string; traceId?: string; turnNo?: number }`。编辑 `useAgentTurn.ts` 的 `onEvent`，收到 `assistant_message_completed` 时更新 traceId、可记录 messageId 和 turnNo，用于后续 rehydrate；收到 `error` 时只要求 `message`，`errorCode` fallback 为 `STREAM_ERROR`，`traceId` fallback 为空。`sseEventDataSchemas.error` 同步改为只要求 `message`，其他字段 optional。工具事件 payload 在后端修复前可能为空，前端只应显示“工具事件缺少详情”或保持低噪音，不把空字符串作为真实工具名展示。

第五阶段修消息、历史和附件模型。编辑 `api/messages.ts`，把 `SendMessageRequest.packageBinding` 改为顶层 `packageId?: string`，`attachments` 中 `PACKAGE_REFERENCE.packageId` 改为 string；如果保留 `UPLOAD_IMAGE`，字段必须能带后端所需的 `sourceRef`，否则不要在 UI 暴露单图直传入口。`getHistory()` 删除 `sinceSeq` 参数，默认 `page: 1`，返回类型改为 `Page<HistoryMessage>` 或专用 `HistoryPage`，从 `items/records` 读取消息；`HistoryMessage.metadata` 后端是 JSON string，应在 adapter normalize 时 `JSON.parse`，失败时保留原始字符串到 `metadataRaw` 或置空并记录一次开发期 warning。把 `compactionMarker` 和 `compactionBoundary` 映射到前端需要的 `isCompactionBoundary` / `isCompactionSummary`。

第六阶段重写会话附件上传 adapter。不要删除 `api/attachments.ts` 的导出，也不要让 `uploadAttachment()` 抛“不可用”错误；应把它改成普通文件上传调用端口的包装函数。实现方式是：`uploadAttachment(conversationId, file, metadata)` 不再使用 `conversationId` 拼接 `/attachments` 路径，而是复用 `api/packages.ts` 中的上传能力。若当前附件入口可以走完整上传状态机，则由 Composer 或上传 runtime 调用分片上传，完成后取得 `packageId`；若需要在 adapter 内保留简单路径，则调用普通整文件上传兼容端点或项目已有的文件上传函数。返回值应 normalize 为可直接参与消息发送的结构，例如 `{ packageId: String(packageId), attachment: { type: 'PACKAGE_REFERENCE', packageId: String(packageId) } }`。Composer 拖文件拿到该结果后，发送消息时带 `{ packageId, attachments: [attachment] }`。如果 `file` 不是后端素材包上传可接受的 zip 或普通文件格式，应在调用普通上传前用现有上传校验给出格式提示，而不是访问不存在的 conversation attachment endpoint。

第七阶段修任务、下载、素材包、Auth 类型。编辑 `api/tasks.ts` 增加 `TaskSummary`、`TaskStatusView.skipped`、`TaskStatusView.lastError`、`DownloadHandle.contentType`、`DownloadHandle.sizeBytes`；`listConversationTasks` 返回 `Page<TaskSummary>`。编辑 `types/upload.ts` 和 `api/packages.ts`，为 `PackageDetail` 增加 normalize 函数，把后端 `id` 映射到 `packageId`，保留 `name/fileHash/minioZipKey/docKey/deletedAt/createdAt/updatedAt` 等后端真实字段中前端需要的部分；不要假设 `PackageDetail.images` 存在，图片仍通过 `listPackageImages` 拉取。编辑 `api/auth.ts` 和 `runtime/authSession.ts`，删除 `AuthUser.id`，把 `userId` 收敛为 number，所有下游改用 `userId` 或 username fallback。

第八阶段收敛上传错误码。编辑 `utils/error.ts`、`upload/chunkWorker.ts` 和对应测试，把旧错误码 `FILE_HASH_INVALID`、`FILE_SIZE_OUT_OF_RANGE`、`FILE_HASH_ALREADY_EXISTS` 从主要分支移除或仅作为历史 fallback；新增或确认 `FILE_HASH_MISMATCH`、`UPLOAD_TOO_LARGE`、`PACKAGE_DEDUP_CONFLICT`、`PACKAGE_ALREADY_REFERENCED`。`UPLOAD_RATE_LIMITED` 不再作为后端业务码处理；429 可以保留通用网络退避，但不显示“上传限流”专用文案，除非 `ApiError.status === 429` 来自通用 `RATE_LIMITED`。

第九阶段补测试。优先新增 adapter 和 runtime 测试，而不是依赖完整浏览器。`api/conversations` 测试验证 query 是 `includeArchived=false&page=1` 且 `id` normalize 为 `conversationId`。`useTask` 测试用 fake STOMP 断言订阅 destination 是连字符 topic，并用 `occurredAt` 帧更新 finishedAt。`useAgentTurn` 测试覆盖 challenge 两阶段：第一次只调 `/challenge`，第二次直接 `/submit` 且带 challengeId。`api/messages` 测试覆盖历史 `records/items` 转换、metadata JSON parse、无 `sinceSeq`。`api/tasks` 测试覆盖 TaskSummary、DownloadHandle 和 cancel result 类型。`ChatPage` 或轻量组件测试覆盖 `Composer` 接收 `sending` prop。

## Concrete Steps

从仓库根目录 `D:\study\PixFlow` 开始。先确认工作区状态，避免覆盖他人改动：

    git status --short

搜索本计划涉及的旧契约：

    rg -n "task-progress|completedAt|cancelledAt|lastTaskId|:busy=|assistant_message_completed|packageBinding|sinceSeq|archived|page: 0|uploadAttachment|FILE_HASH_INVALID|FILE_SIZE_OUT_OF_RANGE|FILE_HASH_ALREADY_EXISTS|UPLOAD_RATE_LIMITED" pixflow-web\src

第一组编辑会话和 Chat：

    pixflow-web/src/api/conversations.ts
    pixflow-web/src/stores/conversations.ts
    pixflow-web/src/pages/ChatPage.vue
    pixflow-web/src/components/chat/Composer.vue

预期修改后，`listConversations()` 支持 `includeArchived`，store 默认 `page: 1`；`ChatPage.vue` 不再出现 `lastTaskId` 和 `:busy`；`Composer` 的 `canSend` 对 `modelValue` 做空值保护。

第二组编辑任务：

    pixflow-web/src/api/tasks.ts
    pixflow-web/src/runtime/useTask.ts
    pixflow-web/src/stores/tasks.ts
    pixflow-web/src/components/tasks/TaskProgressCard.vue

预期修改后，`useTask.ts` 中只出现连字符 topic；`completedAt` 和 `cancelledAt` 不再作为 WS frame 字段；`TaskState.progress.skipped` 和 `lastError` 可被后端快照和 WS 帧更新。

第三组编辑 HITL 和 SSE：

    pixflow-web/src/api/confirm.ts
    pixflow-web/src/types/agent.ts
    pixflow-web/src/runtime/useAgentTurn.ts
    pixflow-web/src/components/chat/ChallengeDialog.vue
    pixflow-web/src/components/chat/ProposalCard.vue

预期修改后，`confirm()` 的第二次带答案调用不再访问 `/challenge`；`submitConfirm()` body 带 `challengeId`；`assistant_message_completed` 在事件 union 和 handler 中存在；SSE error payload 只要求 `message`。

第四组编辑消息、附件和上传：

    pixflow-web/src/api/messages.ts
    pixflow-web/src/api/attachments.ts
    pixflow-web/src/components/chat/Composer.vue
    pixflow-web/src/upload/chunkWorker.ts
    pixflow-web/src/utils/error.ts

预期修改后，消息请求不再有 `packageBinding`；历史请求不再有 `sinceSeq`；会话附件上传 adapter 不再调用不存在的 `/attachments`，而是复用普通文件/素材包上传端口并返回 `PACKAGE_REFERENCE`；旧上传错误码不再是主要分支。

第五组编辑素材包、文件和 auth 类型：

    pixflow-web/src/api/packages.ts
    pixflow-web/src/types/upload.ts
    pixflow-web/src/pages/FilesPage.vue
    pixflow-web/src/api/auth.ts
    pixflow-web/src/runtime/authSession.ts

预期修改后，`PackageDetail` 经 adapter normalize 后稳定提供 `packageId`；页面不依赖 `PackageDetail.images`；`AuthUser` 不再有 `id`。

每完成一组编辑，运行相关快速检查：

    pnpm --dir pixflow-web typecheck

完成全部编辑后运行：

    pnpm --dir pixflow-web test

如果测试失败，优先修复与本计划相关的类型、adapter 和 runtime 测试；若失败来自无关既有测试，记录具体失败文件和断言，不要用删除测试绕过。

## Validation and Acceptance

会话与 Chat 验收：未登录之外的鉴权前提满足后，进入 `/chat/new?q=hello`。前端应请求 `POST /api/conversations`，随后地址变为 `/chat/{真实conversationId}`，只发送一次 `POST /api/conversations/{conversationId}/messages`。会话列表请求应为 `/api/conversations?includeArchived=false&page=1&size=50` 或等价默认值，不出现 `archived=false&page=0`。发送中或流式输出中，Composer 发送按钮不可重复触发新回合；点击停止走 `abort()`。

HITL 验收：在 fake API 或真实需要 challenge 的提案下，第一次确认只调用 `/challenge` 空 body；若返回 `needChallenge=true`，弹窗保存 `challengeId`；用户输入答案后，第二次确认直接调用 `/submit`，body 为 `{ challengeId, challengeAnswer }`，并带同一个 `Idempotency-Key`。`ConfirmationSubmitResponse.status='CONFIRMED'` 不应让前端类型报错，`taskId` 应写入 `AgentTurnSummary.taskId` 并挂载任务卡。

SSE 验收：收到 `assistant_delta` 时追加文本；收到 `assistant_message_completed` 时不被 unknown event 吞掉；收到 `completed` 时进入 completed；收到 `error` 且 payload 只有 `{ message }` 时进入 error，`errorCode` fallback 为 `STREAM_ERROR`。工具事件 payload 为空时 UI 不展示虚假的工具名。

任务验收：任务卡创建后订阅 destination 必须是 `/topic/task-progress-{conversationId}-{taskId}`。用 fake frame `{ taskId, done: 1, total: 2, failed: 0, skipped: 0, status: 'RUNNING', occurredAt: '...' }` 推送后，前端 progress 和 phase 更新；终态 frame 用同一个 `occurredAt` 写 `finishedAt`。取消接口响应 `{ taskId, cancelled: false }` 也能被类型接受。

消息和附件验收：历史消息请求不带 `sinceSeq`，响应 `records` 或 HTTP client normalize 后的 `items` 能渲染为消息列表。`metadata` 是 JSON string 时被 parse 为对象；parse 失败不崩页面。Composer 拖文件或附件入口不再调用 `/api/conversations/{cid}/attachments`，也不直接报错；它应调用普通文件/素材包上传端口，上传完成后拿到 `packageId`，再用顶层 `packageId` 和 `PACKAGE_REFERENCE.packageId` 字符串发送消息。

文件与任务结果验收：`PackageDetail` 即使后端只返回 `id`，前端仍能得到 `packageId`；`FilesPage` 不依赖 `pkg.images`；`DownloadHandle` 可读取 `contentType` 和 `sizeBytes`；`TaskSummary` 会话任务列表使用平铺 `done/total/failed`。

自动化验收：

    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test

两条命令应退出 0。新增或更新测试至少覆盖会话 query、WS topic、HITL challenge/submit、SSE error optional 字段、消息历史响应、任务 API 类型和旧上传错误码迁移。

## Idempotence and Recovery

本计划改动应是可重复执行的。adapter normalize 函数可以多次调用并返回同样的前端模型，不应修改原始对象导致测试顺序相关。重写 `uploadAttachment()` 时，要先搜索调用点并迁移到普通文件/素材包上传链路，保留入口的调用语义，避免留下访问不存在 endpoint 的死路径。

如果实现过程中发现后端已经改为与报告不同的新契约，先用当前源码和设计文档确认真实行为，再更新本计划的 `Surprises & Discoveries` 和 `Decision Log`。不要为了通过旧测试而恢复已确认错误的前端契约。

如果某个接口必须短期兼容新旧两种后端字段，例如 `ConversationView.id` 与未来可能出现的 `conversationId`，兼容应只放在 adapter normalize 中，并加测试说明优先级。页面和 store 只消费规范化后的 `conversationId`。

如果测试或类型检查发现大量连带类型错误，优先把后端模型类型和前端视图模型分开命名。例如 `BackendConversationView`、`ConversationSummary`；`BackendMessageView`、`HistoryMessage`。不要把“后端真实字段”和“前端展示字段”混在同一个接口里继续扩大歧义。

如果外部依赖仍未修复，例如后端 SSE `tool_*` payload 为空，前端测试应断言“不会崩溃且不会展示虚假详情”，而不是断言工具名存在。待后端修复后再补充展示测试。

## Artifacts and Notes

用户报告中本计划覆盖的前端项摘要：

    A1 useTask WS topic: /topic/task-progress/{cid}/{tid} -> /topic/task-progress-{cid}-{tid}
    A2 WS finished time: completedAt/cancelledAt -> occurredAt
    A3 HITL submit body: add challengeId
    A4 conversation list page: 0 -> 1
    A5 ChatPage state.lastTaskId -> state.taskId
    A6 Composer :busy -> :sending
    A8 replace /attachments call with ordinary file/package upload wrapper
    A9 add assistant_message_completed event
    A10 SSE error only requires message
    A11 archived query -> includeArchived

    B1 map ConversationView.id -> conversationId
    B2 HistoryResponse -> PageResponse<MessageView>
    B3 packageBinding -> top-level packageId string
    B5 ConfirmationSubmitResponse status CONFIRMED
    B6 ConfirmationToken has token only, no expiresAt
    B7 CancellationResult has taskId and cancelled boolean
    B8 TaskState adds skipped and lastError
    B9 listConversationTasks returns TaskSummary
    B10 DownloadHandle adds contentType and sizeBytes
    B11 PackageDetail maps backend id/name fields
    B12 imageId remains string in TS, validate numeric path usage
    B13 AuthUser removes id
    B15 MessageView.metadata parse from JSON string
    B16 compactionMarker/compactionBoundary map to UI booleans

前端不负责但必须记录的外部依赖：

    A7 SSE tool_* payload currently empty because backend projection drops record fields.
    D3/D4 backend HITL threshold and token verification are not enforced yet.
    D6 backend SSE heartbeat property is unused; frontend timeout remains byte-idle based.
    D11-D14 Redis/MinIO internal upload keys are backend internals; frontend must not depend on them.

## Interfaces and Dependencies

会话 adapter 最终前端模型：

    export interface ConversationSummary {
      conversationId: string
      title?: string
      updatedAt: string
      packageId?: string | null
    }

后端到前端 normalize 规则：

    conversationId = String(raw.conversationId ?? raw.id)
    packageId = raw.packageId == null ? null : String(raw.packageId)

消息发送请求：

    export interface SendMessageRequest {
      prompt?: string
      attachments?: Array<
        | { type: 'PACKAGE_REFERENCE'; packageId: string; sourceRef?: string | null; metadata?: Record<string, unknown> | null }
        | { type: 'UPLOAD_IMAGE'; attachmentId?: string; sourceRef: string; metadata?: Record<string, unknown> | null }
      >
      packageId?: string
      metadata?: Record<string, unknown>
    }

HITL 接口：

    challenge(conversationId: string, proposalId: string): Promise<ChallengeOrToken>
    submit(
      conversationId: string,
      proposalId: string,
      body: { challengeId?: string; challengeAnswer?: string },
      idempotencyKey: string
    ): Promise<{ proposalId: string; taskId: string; status: string }>

任务接口：

    export interface TaskStatusView {
      taskId: string
      status: TaskStatus
      taskType: 'IMAGE_PROCESS' | 'IMAGE_GEN'
      progress: { done: number; total: number; failed: number }
      skipped: number
      lastError?: string | null
      createdAt: string
      startedAt?: string
      finishedAt?: string
    }

    export interface TaskSummary {
      taskId: string
      taskType: 'IMAGE_PROCESS' | 'IMAGE_GEN'
      status: TaskStatus
      done: number
      total: number
      failed: number
      createdAt: string
      finishedAt?: string | null
    }

    export interface DownloadHandle {
      url: string
      expiresAt: string
      contentType?: string
      sizeBytes?: number | null
    }

    export interface CancellationResult {
      taskId: string
      cancelled: boolean
    }

WS frame：

    export interface TaskProgressFrame {
      taskId: string
      done: number
      total: number
      failed: number
      skipped: number
      status: TaskStatus
      occurredAt: string
    }

WS destination：

    /topic/task-progress-{conversationId}-{taskId}

SSE event union：

    type AgentEventName =
      | 'assistant_delta'
      | 'assistant_message_completed'
      | 'tool_call_ready'
      | 'tool_started'
      | 'tool_result'
      | 'transition'
      | 'completed'
      | 'error'

SSE error payload：

    export interface ErrorEventPayload {
      message: string
      errorCode?: string
      traceId?: string
    }

Auth user：

    export interface AuthUser {
      userId: number
      username: string
      displayName?: string | null
      status?: string
    }

## Revision Notes

2026-07-06 / Codex: 新建本 ExecPlan。原因是用户要求阅读前端设计文档和不一致报告，并按 `PLANS.md` 撰写一个“修复前后端不一致中的前端部分”的中文计划，说明修复机制和思路。本计划将范围限定为前端 adapter、runtime、store、页面和前端测试，并把后端仍需修复的 SSE record 投影、HITL 真实校验、heartbeat 等项标为外部依赖。
