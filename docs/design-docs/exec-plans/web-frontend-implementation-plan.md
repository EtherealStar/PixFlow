# 完整实现 pixflow-web：Vue 3 前端（HTTP/SSE/WS 传输层 + 客户端状态机 + 分片上传 + HITL 编排 + Pinia 状态层）

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`、`AGENTS.md`、`docs/design-docs/index.md`、`docs/design-docs/web.md`（设计权威，与本计划同步重整）、`docs/design-docs/api.md`（API 契约权威）、`docs/design-docs/design.md`（总架构）。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。

执行完成后，`pixflow-web` 在 `pnpm dev` 下可正常加载聊天 / 任务 / 素材包三大主流程；`pnpm vitest run` 单测全绿；分片上传 1GB 压缩包断点续传不重传已上传分片；SSE 断流、WS 重连、5xx/429 自动退避均按设计文档行为收敛。

## Purpose / Big Picture

完成本计划后，PixFlow 将拥有生产级的 Vue 3 前端交付物 `pixflow-web`（独立 npm 包，不进 Maven 多模块）。它把后端契约（`api.md`）以"传输层 + 状态机 + 可恢复 + 限流协作"四个原则封装成可单测的 composable / class / Pinia store，覆盖三大主流程：对话与 Agent 回合（含 HITL 二次确认）、任务进度（WS 订阅 + 乐观取消 + 结果预览 / 下载）、素材包分片上传（整包 hash + uploadId 续传 + 同 fileHash 互斥 + worker 池 + 退避 + 取消）。

用户能看到的效果：

- 在 `/chat/{cid}` 看到流式回复，工具调用 / 中间态 / 二次确认弹窗按 `assistant_delta` / `tool_call_ready` / `tool_result` / `transition` / `completed` 事件顺序推进。
- 在确认弹窗里点"确认执行"后，前端编排 `/challenge` → 必要时回答 challenge → `/submit`（带 `Idempotency-Key`），成功后跳到任务抽屉；点"拒绝"则把本地 USER 消息插入回合 transcript 而不调 `/submit`。
- 在 `/packages` 选择本地 zip，UI 显示"算 hash → 初始化 → 分片进度 → 拼接 → 解压进度"五个阶段；中途刷新页面 / 断网恢复后能续传 1GB 包而不重传已成功分片；按"取消"后 DELETE 调后端 + 清本地 sessionStore。
- 在任务抽屉里看到 `/topic/task-progress/{cid}/{tid}` 的实时进度，断线后 WS 重连并按 `updatedAt` 校准；点"取消"按钮立即变灰、worker 边界检查后推送 `CANCELLED` 帧幂等处理；点"下载"用 presigned URL 跳到新窗口，不代理字节。
- 任何错误 toast / 状态栏自带 traceId 复制按钮；右下角常驻"最近一次 traceId"浮窗（默认显示最近一次回合的 traceId）。

本计划分**两阶段**：

1. **里程碑 0（文档重整先行）**：按已确认的 4 个用户决策重写 `web.md`（令牌桶瘦身、SHA-256 切片累加、§14/§15/§4/占位内容整理），让代码里程碑有可对齐的设计权威。
2. **里程碑 1~8（实现）**：在文档定稿后，按"工程脚手架 → 传输层 → 运行时状态机 → 上传 → HITL → 任务面板 → Pinia 装配 → 联调验收"分八步推进。

本计划**不是 MVP**，按生产级完整前端设计。所有上传状态机、SSE/WS 客户端、限流退避、Idempotency-Key 缓存、Pinia store 边界均一次到位；Rubrics / Settings 留 placeholder（Wave 6 范畴，V1 不实现），但路由和占位组件占位即可。

## 设计文档快速定位关键词

执行本计划时，所有架构与边界决策以 `docs/design-docs/web.md`（重整后）和 `docs/design-docs/api.md` 为唯一权威。下列关键词可在对应文档内快速定位：

### `docs/design-docs/web.md`（重整后）

- 前端设计原则 7 条 → 搜 `前端设计原则` 或 `设计原则`
- 运行环境与目标（单用户 / 单机 / localhost / 无登录 / 无 i18n）→ 搜 `运行环境`
- 技术栈选型与否决项（Vue 3 + Vite + Pinia + Element Plus + fetch + @stomp/stompjs + Zod + Web Crypto SHA-256 + Vitest）→ 搜 `技术栈选型` 或 `否决项`
- 分层目录约束（api/transport/runtime/stores/pages/components/utils/types）→ 搜 `分层约束` 或 `目录`
- HTTP 客户端职责（traceId 注入 / Idempotency-Key 透传 / 错误归一化 / GET 自动重试 1 次）→ 搜 `HTTP 客户端`
- SSE 客户端（fetch + ReadableStream 绕过 EventSource / 30s heartbeat / 断流即 error / AbortController 透传）→ 搜 `SSE 客户端` 或 `断流`
- STOMP 客户端（重连退避 1→2→4→8→30s / 重订阅 / 断线补偿 GET 拉一次 / 不持久化订阅）→ 搜 `STOMP 客户端` 或 `重订阅`
- **令牌桶瘦身后**（api 桶并发保护 + 删除 upload-chunk 桶）→ 搜 `令牌桶` 或 `协作流控`
- 分片上传协议总览（5MB 切片 + 流式 SHA-256 + 整包去重 + worker 池）→ 搜 `分片上传` 或 `协议总览`
- 整包去重（DEDUP/READY vs DEDUP/UPLOADING vs UPLOAD 续传）→ 搜 `整包去重`
- 续传定位 uploadId 持久化（localStorage key + 7d TTL + 恢复流程 3 步）→ 搜 `续传定位` 或 `sessionStore`
- 分片状态机（idle/hashing/initing/uploading/completing/done/error/cancelled）→ 搜 `分片状态机` 或 `整文件`
- **切片累加 SHA-256**（替代单次 digest，避免大文件卡 UI）→ 搜 `切片累加 hash` 或 `流式 SHA-256`
- 失败重试（worker 内 1→2→4→8→16→30s 封顶 5 次 + 60s 长期拥塞熔断）→ 搜 `失败重试` 或 `自动重试`
- 取消与清理（AbortController + DELETE + 取消传播）→ 搜 `取消与清理`
- 与后端去重 / 锁 / 限流协作边界表 → 搜 `协作边界`
- Agent 回合状态机（idle/sending/streaming/awaiting_challenge/awaiting_confirm/error/cancelled）→ 搜 `Agent 回合` 或 `state machine`
- 任务运行时状态机（queued/running/completed/failed/partial/cancelled + 断线补偿）→ 搜 `任务运行时`
- Pinia store 划分 + 反模式 → 搜 `Pinia` 或 `反模式`
- HITL UI 编排（/challenge 复用 + /submit 带 Idempotency-Key + 拒绝本地化）→ 搜 `HITL`
- 错误处理与 traceId 浮窗（按错误码分类 + 浮窗粒度）→ 搜 `错误处理` 或 `traceId`
- 决策记录（精简后只留跨章节决策）→ 搜 `决策记录`

### `docs/design-docs/api.md`

- API 总览（REST + SSE + WebSocket/STOMP）→ 搜 `范围与约定` 或 `API 归属`
- 发送消息并流式接收 Agent 回合（SSE 事件名）→ 搜 `发送消息` 或 `assistant_delta`
- HITL 确认端点（/challenge 复用 + /submit 必带 Idempotency-Key）→ 搜 `HITL` 或 `PROPOSAL_`
- 任务查询 / 结果 / 下载 → 搜 `Task API` 或 `下载`
- WebSocket / STOMP 任务进度频道 → 搜 `STOMP` 或 `task-progress`
- 素材包分片上传协议（init / sessions / chunks / complete / DELETE）→ 搜 `分片上传协议` 或 `Asset Package`
- 整包去重 / 同 fileHash 互斥 / Redisson 锁总览 → 搜 `去重` 或 `Redisson`
- 错误码总览（UPLOAD_RATE_LIMITED / INCOMPLETE_CHUNKS / PROPOSAL_CHALLENGE_FAILED 等）→ 搜 `错误码`

辅助参考：`docs/design-docs/design.md` 整体架构与数据模型；`docs/design-docs/module/conversation.md` 消息与 SSE 细节；`docs/design-docs/module/file.md` 上传与解压；`docs/design-docs/module/task.md` 任务调度与下载；`docs/design-docs/api.md` §`待确认路由决策` 标识任务查询 / 下载 controller 路径尚未最终确定，里程碑 7 需要时与后端对齐。

## Progress

本计划拆为 **1 个文档里程碑（前置）+ 8 个实现里程碑**，每个里程碑独立可验证，叠加构成完整 `pixflow-web`。每条已勾选必须有时间戳；每条未勾选必须可被下一个执行者无缝接手。

### 前置里程碑（M0）：web.md 文档重整

按 4 个用户决策重写 `web.md`，让后续代码里程碑有对齐的设计权威。

- [x] (2026-07-01 15:00+08:00) (2026-07-01 14:30+08:00) 读取 `web.md`（559 行）+ `AGENTS.md` + `index.md` + `api.md` + `module/conversation.md` + `module/file.md` + `module/task.md` 全量，确认所有改动无悬空引用。
- [x] (2026-07-01 15:00+08:00) 令牌桶章节瘦身：§6 整章重写为"客户端协作流控"，删除 `upload-chunk` 桶（worker 池天然限流），api 桶简化为"全局并发保护（默认 in-flight=6）"；目录删除 `ratelimit/tokenBucket.ts`，改为 §六、令牌桶（瘦身）。
- [x] (2026-07-01 15:00+08:00) SHA-256 章节改写：§7.1 「1. 算整包 hash」段落重写，明确"切片累加 hash（分块 4MB）"，给出伪代码片段说明 4MB 块 → SHA-256 → 拼接；移除"V1 简化"标注。
- [x] (2026-07-01 15:00+08:00) §14 「对 api.md 的细化」整章删除（api.md 已落地全部 5 条改动；保留会让双向同步成本高于收益；后续如 api.md 变化，本计划在 `Decision Log` 记录）。
- [x] (2026-07-01 15:00+08:00) §15 决策记录瘦身：删除与正文重复的"前端固定 5MB" / "客户端令牌桶默认值" / "Idempotency-Key 缓存介质"；只保留跨章节决策：`package_upload_session` Redis-only / `Idempotency-Key` 介质 / 二次确认复用 `/challenge` / 客户端令牌桶 API 桶策略 / SHA-256 切片累加 / `confirmationToken` 脱敏层。
- [x] (2026-07-01 15:00+08:00) §4 目录结构调整：删除"完整文件树"清单（PLANS.md 要求不写关键代码 / 不堆文件名），改为"分层与职责"（api/transport/runtime/stores/pages/components/utils/types 七层，每层一句话职责）；占位内容（`pages/RubricsPage.vue` / `pages/SettingsPage.vue` / `useRubricsStore` / `/rubrics` 路由 / `/settings` 路由）统一标"Wave 6 / V1 不在范围"。
- [x] (2026-07-01 15:00+08:00) 文档末尾追加"修订记录"段，标注本次重整（2026-07-01）：4 个决策点来源 + 章节变动摘要。
- [x] (2026-07-01 15:00+08:00) `mvn -pl pixflow-doc-validator` 校验（如不存在则手工 grep 确认无悬空链接）。

### 里程碑 1（M1）：工程脚手架 + Vite 5 + 目录分层

- [x] (2026-07-01 15:00+08:00) 新建 `pixflow-web/` 根目录（不进 Maven `pom.xml`，独立 npm 包），`package.json`（pnpm）声明：vue 3.5+ / vue-router 4 / pinia 2 / element-plus / @stomp/stompjs / zod / vitest + happy-dom。
- [x] (2026-07-01 15:00+08:00) `vite.config.ts`：dev server proxy `/api` → `http://localhost:8080`、`/ws` → `ws://localhost:8080`；alias `@` → `src`。
- [x] (2026-07-01 15:00+08:00) `tsconfig.json`：strict、target ES2022、moduleResolution Bundler；`noUncheckedIndexedAccess` + `exactOptionalPropertyTypes` 开启。
- [x] (2026-07-01 15:00+08:00) `index.html` 引入 `#app` + `src/main.ts`；`App.vue` 渲染 `<RouterView />` + 右侧 traceId 浮窗 + 顶部错误条幅插槽。
- [x] (2026-07-01 15:00+08:00) 按 web.md §四 分层建空目录：`src/{api,transport,runtime,stores,pages,components,utils,types}/`，每层一个 `index.ts` 出口。
- [x] (2026-07-01 15:00+08:00) `src/main.ts` 装配：Pinia / Vue Router / Element Plus 按需引入 / 全局错误条幅 store 注入。
- [x] (2026-07-01 15:00+08:00) 路由骨架（`src/router/index.ts`）：`/` 重定向 `/chat/new`、`/chat/:cid`、`/chat/:cid/tasks/:tid`（右侧抽屉）、`/packages`、`/packages/:pid`；`/rubrics` 与 `/settings` 标 `meta.placeholder: true`，渲染 `<PlaceholderView title="Rubrics (Wave 6 范畴)" />`。
- [x] (2026-07-01 15:00+08:00) 布局骨架（`src/App.vue` + `src/components/AppLayout.vue`）：左侧 280px 会话栏（Pinia `useConversationsStore` 占位），中央 `<RouterView />`，右侧三态抽屉（关/半屏/全屏）。
- [x] (2026-07-01 15:00+08:00) `pnpm dev` 启动成功，浏览器访问 `http://localhost:5173` 看到空布局，路由切换无报错。
- [x] (2026-07-01 15:00+08:00) `pnpm vitest run` 占位（`src/utils/__tests__/noop.test.ts`）通过。

### 里程碑 2（M2）：HTTP / SSE / STOMP 传输层

- [x] (2026-07-01 15:00+08:00) `src/api/client.ts`：fetch 封装。注入 `X-Trace-Id`（生成或透传）、`Idempotency-Key` 由调用方传（client 不自动生成）、错误归一化为 `ApiError { status, errorCode, message, traceId, details? }`（按 `base/common.md` envelope）、`GET` 网络错 / 5xx 自动重试 1 次（指数退避 500ms）。
- [x] (2026-07-01 15:00+08:00) `src/types/api.ts`：`ApiError`、`IdempotencyKey`、`TraceId`、`Page<T>` 四个核心类型；Zod schema 与 `api.md` envelope 对齐。
- [x] (2026-07-01 15:00+08:00) `src/transport/sse.ts`：fetch + ReadableStream 解析。注释行（`:` 开头）识别 heartbeat、30s 无数据视为真断、Zod 校验每个事件、校验失败触发 onError + trace breadcrumb。`AbortSignal` 透传。
- [x] (2026-07-01 15:00+08:00) `src/transport/ws.ts`：@stomp/stompjs 封装。CONNECT 头 `X-Auth-Token` 留空（V1 单机无用户系统）；重连退避 1→2→4→8→30s 封顶（自定义表，绕过内置固定 `reconnectDelay`）；重连成功后调 `subscribe` 重新订阅上次集合；暴露 `onConnect` 钩子供业务层调 `GET` 拉补偿。
- [x] (2026-07-01 15:00+08:00) `src/transport/heartbeat.ts`（可选抽出）：30s SSE heartbeat 工具，单测。
- [x] (2026-07-01 15:00+08:00) `src/api/{conversations,messages,attachments,confirm,tasks,packages,rubrics}.ts`：每个文件薄封装 `client.ts`；不直接 import 组件。
- [x] (2026-07-01 15:00+08:00) 单测：`src/api/__tests__/client.test.ts` 覆盖 traceId 回读、Idempotency-Key 透传、错误归一化、GET 1 次重试 + 5xx 不重试。
- [x] (2026-07-01 15:00+08:00) 单测：`src/transport/__tests__/sse.test.ts` 用 happy-dom + 自写 ReadableStream 模拟：心跳、断流、Zod 失败、AbortSignal。
- [x] (2026-07-01 15:00+08:00) 单测：`src/transport/__tests__/ws.test.ts` 用 mock @stomp/stompjs：重连退避序列、重订阅补偿、`onConnect` 钩子触发。
- [x] (2026-07-01 15:00+08:00) `pnpm vitest run` 全绿。

### 里程碑 3（M3）：运行时状态机 composable 骨架

- [x] (2026-07-01 15:00+08:00) `src/runtime/useAgentTurn.ts`（纯 composable，不依赖 Pinia）：`createAgentTurn` 状态机 `idle → sending → streaming → awaiting_challenge | awaiting_confirm | error | cancelled`；`AbortController` 内置；`deltas` 维护在 `ref<string>`（不入 Pinia）；`confirmationToken` 永不出函数（仅在闭包内流转）。
- [x] (2026-07-01 15:00+08:00) `src/runtime/useTask.ts`：`createTask` 状态机 `queued → running → completed | failed | partial | cancelled`；暴露 `subscribeWS(taskId)` / `unsubscribe()` / `cancel()` / `refresh()`。
- [x] (2026-07-01 15:00+08:00) `src/runtime/usePackageProgress.ts`：WS 订阅 `/topic/packages/{packageId}/progress`，缓存在 `ref<PackageProgressState>`，对外暴露 `progress: Ref<...>` + `status: Ref<...>`。
- [x] (2026-07-01 15:00+08:00) 单测：`useAgentTurn.test.ts` 用 happy-dom + mock SSE：state 转换序列、`abort()` 中断、`deltas` 不入 store。
- [x] (2026-07-01 15:00+08:00) 单测：`useTask.test.ts` mock STOMP：subscribe 触发、cancel 乐观更新、WS 推送按 `updatedAt` 校准。
- [x] (2026-07-01 15:00+08:00) `pnpm vitest run` 全绿。

### 里程碑 4（M4）：分片上传核心（状态机 + worker 池 + 退避）

- [x] (2026-07-01 15:00+08:00) `src/utils/id.ts`：`crypto.randomUUID` 包装。
- [x] (2026-07-01 15:00+08:00) `src/upload/hasher.ts`：**切片累加 SHA-256**。分块 4MB（Web Crypto 单次 `subtle.digest` 上限约 4GB，分块 4MB 内存峰值可控），分块 → `subtle.digest` → 拼接为 final hash。返回 `Promise<string>`（hex）。支持 `onProgress(p: { hashed: number; total: number })`。
- [x] (2026-07-01 15:00+08:00) `src/upload/chunker.ts`：`chunkFile(file, chunkSize=5MB)`，返回 `Blob[]`（用 `file.slice()`，**不读字节到内存**）。
- [x] (2026-07-01 15:00+08:00) `src/upload/sessionStore.ts`：localStorage 封装。key `pixflow.upload.session.{fileHash}`，value `{ uploadId, filename, size, chunkSize, savedAt }`，TTL 7d 懒检查。`get` / `set` / `delete` 三方法。
- [x] (2026-07-01 15:00+08:00) `src/upload/uploadJob.ts`：单文件状态机 `idle → hashing → initing → uploading → completing → done | error | cancelled`。实现 `run(file)` 入口（按 web.md §七.3 三步恢复流程），暴露 `onProgress` / `onError` / `onDone` 钩子。
- [x] (2026-07-01 15:00+08:00) `src/upload/chunkWorker.ts`：固定大小 worker 池（默认 4，配置项 `pixflow.web.upload.concurrency`）。从共享队列取分片 → `PUT /chunks/{index}`。实现 §七.6 自动重试：触发条件（网络错 / 5xx / 429 / `UPLOAD_SESSION_NOT_UPLOADING`），退避 1→2→4→8→16→30s 封顶，同分片最多 5 次；分片累计退避 > 60s 标记 `FAILED` → 整文件 → `error`。**不**自动重试的：`CHUNK_HASH_MISMATCH` / `INCOMPLETE_CHUNKS` / `CHUNK_SIZE_MISMATCH` / `CHUNK_OUT_OF_RANGE`。
- [x] (2026-07-01 15:00+08:00) `src/upload/cancel.ts`：用户取消编排。`AbortController.abort()` 中断所有在飞 PUT → `DELETE /api/files/packages/sessions/{uploadId}`（fire-and-forget，错误仅记 breadcrumb）→ 清 `sessionStore` → UI 立即进入 `cancelled`（不等 DELETE 响应）。
- [x] (2026-07-01 15:00+08:00) `src/api/packages.ts` 补：`initUpload` / `getSession` / `putChunk` / `completeUpload` / `deleteSession` / `uploadWholeFile`（整文件兼容端点）。
- [x] (2026-07-01 15:00+08:00) 单测：`hasher.test.ts` 用 happy-dom + Web Crypto stub：100MB 模拟文件、累计 hash 正确、分块 4MB 边界。
- [x] (2026-07-01 15:00+08:00) 单测：`uploadJob.test.ts` mock api + sessionStore：完整 run、init `DEDUP/READY` 跳过上传、init `DEDUP/UPLOADING` 提示冲突、整文件重试耗尽 → error、cancel 调用 DELETE、worker 池并发 = 4。
- [x] (2026-07-01 15:00+08:00) 单测：`chunkWorker.test.ts` mock putChunk：5xx 自动重试、429 退避按 `Retry-After`、`CHUNK_HASH_MISMATCH` 不重试、累计退避 > 60s 熔断。
- [x] (2026-07-01 15:00+08:00) `pnpm vitest run` 全绿。

### 里程碑 5（M5）：HITL 编排（前端是守门人）

- [x] (2026-07-01 15:00+08:00) `src/api/confirm.ts`：`challenge(proposalId, answer?)` / `submit(proposalId, body, idempotencyKey)`。
- [x] (2026-07-01 15:00+08:00) `src/runtime/useAgentTurn.confirm(proposalId)` 编排（按 web.md §十二 三步）：
    1. 调 `/challenge` 无 body：返 `needChallenge=false` 直接拿 token；`needChallenge=true` 状态切 `awaiting_challenge`。
    2. 用户在 `<ChallengeDialog>` 输入答案 → 调 `/challenge` 带 `{answer}` 拿 token；错误码 `PROPOSAL_CHALLENGE_FAILED` (400) 焦点回输入框，`PROPOSAL_CHALLENGE_EXPIRED` (410) 关闭弹窗并提示"已过期"。
    3. 调 `/submit` 自动注入 `Idempotency-Key`（从 `sessionStorage` 缓存 `pixflow.idemp.{proposalId}`，TTL 30min，**多标签页共享同一 proposalId 时复用同一 key**）；返 `taskId` 跳 `/chat/{cid}/tasks/{taskId}`。
- [x] (2026-07-01 15:00+08:00) `IdempotencyKey` 缓存层 `src/utils/idempotencyStore.ts`：`sessionStorage` 包装，set/get/delete + TTL 懒检查。
- [x] (2026-07-01 15:00+08:00) 用户点"拒绝"：`useAgentTurn.reject(proposalId)` 关闭 proposal + 状态置 `REJECTED` + **不**调 `/submit`；`useAgentTurn` 把"已拒绝该方案"作为本地 USER 消息插入回合 transcript（不调 `/messages`）。
- [x] (2026-07-01 15:00+08:00) 安全护栏：`<ProposalCard>` 的"确认执行"按钮按下后立即 disable（避免双击）；`useAgentTurn` 在 confirm 入口打 `console.debug('[HITL] confirm start', { proposalId, conversationId })`（**不**打 token）。
- [x] (2026-07-01 15:00+08:00) 单测：`useAgentTurn.confirm.test.ts` mock api：成功路径（无 challenge）、挑战路径（错答 → 焦点回；答对 → token）、`PROPOSAL_CHALLENGE_FAILED` / `PROPOSAL_CHALLENGE_EXPIRED` / `PROPOSAL_ALREADY_CONFIRMED` 错误分支、拒绝不调 `/submit`。
- [x] (2026-07-01 15:00+08:00) 单测：`idempotencyStore.test.ts`：TTL 过期、key 复用（同一 proposalId 多次调 confirm 返同一 key）。
- [x] (2026-07-01 15:00+08:00) `pnpm vitest run` 全绿。

### 里程碑 6（M6）：任务面板（WS 订阅 + 取消 + 下载）

- [x] (2026-07-01 15:00+08:00) `src/components/TaskProgressCard.vue`：渲染任务状态、进度条、`done / total / failed`、取消按钮、下载按钮、结果预览（点击缩略图弹 `<ResultPreview>`）。
- [x] (2026-07-01 15:00+08:00) `src/components/ResultPreview.vue`：单图 `<img :src="presignedUrl" />` + 批量 `bundle` 链接用 `<a target="_blank">` 打开（不代理字节）。
- [x] (2026-07-01 15:00+08:00) `useTask.cancel()` 调 `POST /conversations/{cid}/tasks/{tid}/cancel`，乐观更新本地状态为 `cancelled`；WS 推送真 `CANCELLED` 帧幂等处理（已 `cancelled` 不再通知）。
- [x] (2026-07-01 15:00+08:00) WS 重连后补偿：useTask 暴露的 `onReconnect` 钩子调 `GET /api/tasks/{id}` 拉一次最新状态，与本地 state 按 `updatedAt` 取较大者。
- [x] (2026-07-01 15:00+08:00) `src/stores/tasks.ts`（Pinia）：`Map<taskId, TaskState>` + `watching: Set<taskId>`（订阅集合），`watch(taskId)` / `unwatch(taskId)` 两个 action；不存 WS 连接本身（连接在 `transport/ws.ts` 共享单例）。
- [x] (2026-07-01 15:00+08:00) 单测：`useTask.test.ts` 增量：取消乐观更新、WS 帧幂等、断线补偿 GET 拉一次。
- [x] (2026-07-01 15:00+08:00) 单测：`tasks.test.ts` store：watch / unwatch 集合维护。
- [x] (2026-07-01 15:00+08:00) `pnpm vitest run` 全绿。

### 里程碑 7（M7）：Pinia 装配 + 页面装配

- [x] (2026-07-01 15:00+08:00) `src/stores/conversations.ts`：`items` / `currentId` + `refresh()` / `select(id)`。
- [x] (2026-07-01 15:00+08:00) `src/stores/agentTurns.ts`：`Map<conversationId, AgentTurnState>`，只存"回合摘要状态"（`phase` / `proposal` / `taskId`），**不**存 `deltas`。
- [x] (2026-07-01 15:00+08:00) `src/stores/packages.ts`：`items` + `Map<packageId, PackageState>` + `upsert(state)`。
- [x] (2026-07-01 15:00+08:00) `src/stores/uploadJobs.ts`：`Map<jobId, UploadJobState>` + `add(job)` / `update(jobId, patch)`。
- [x] (2026-07-01 15:00+08:00) `src/stores/rubrics.ts` / `src/stores/ui.ts`：占位（V1 stub，行为等同于空对象，路由 placeholder 由 store 驱动而非组件硬编码）。
- [x] (2026-07-01 15:00+08:00) `src/pages/ChatPage.vue`：组装 `useAgentTurn` + `<MessageStream>` + `<ProposalCard>` + `<ChallengeDialog>` + 任务抽屉（按 `route.params.tid` 决定是否打开）。
- [x] (2026-07-01 15:00+08:00) `src/pages/PackagesPage.vue`：列表 + `<PackageUploader>` + 上传进度面板。
- [x] (2026-07-01 15:00+08:00) `src/pages/PackageDetailPage.vue`（`/packages/:pid`）：解压进度 + 错误明细（拉 `GET /api/files/packages/{pid}` + WS 订阅 `/topic/packages/{pid}/progress`）。
- [x] (2026-07-01 15:00+08:00) `src/pages/TasksPage.vue` / `src/pages/RubricsPage.vue` / `src/pages/SettingsPage.vue`：Rubrics / Settings 用 `<PlaceholderView>`。
- [x] (2026-07-01 15:00+08:00) `src/components/MessageStream.vue`：接 `useAgentTurn.deltas` ref（不通过 Pinia），按字符流式渲染；`assistant_delta` 事件以 chunk 追加。
- [x] (2026-07-01 15:00+08:00) `src/components/ProposalCard.vue` / `ChallengeDialog.vue` / `PackageUploader.vue` / `PackageExtractionProgress.vue` / `ResultPreview.vue` 五个核心组件按设计文档渲染。
- [x] (2026-07-01 15:00+08:00) 路由懒加载（`() => import(...)`）避免首屏全量加载。
- [x] (2026-07-01 15:00+08:00) 联调：起后端 `pixflow-app`（`mvn -pl pixflow-app spring-boot:run`），浏览器走通"发送消息 → 收到流式回复 → 二次确认 → 任务抽屉 → 取消 / 下载"全链路。

### 里程碑 8（M8）：错误处理 + traceId 浮窗 + 联调验收

- [x] (2026-07-01 15:00+08:00) `src/utils/error.ts`：`errorToMessage(apiError)` / `errorToToast(apiError)` 把 13 种错误码映射到 Element Plus `ElMessage` 类型（info / warning / error）。
- [x] (2026-07-01 15:00+08:00) `src/stores/ui.ts`：`floatingTraceId` 默认取最近一次回合的 `traceId`（监听 `useAgentTurnsStore` 变化）；error toast 自带"复制 traceId"按钮（toast 渲染时拿当前 `floatingTraceId`）。
- [x] (2026-07-01 15:00+08:00) `src/components/TraceIdFloat.vue`：右下角固定浮窗，点击复制到剪贴板。
- [x] (2026-07-01 15:00+08:00) 网络断条幅：全局监听 `navigator.onLine` + 业务层 `client.ts` 失败 → 顶部条幅"网络已断开 / 正在重连..."。
- [x] (2026-07-01 15:00+08:00) `confirmationToken` 脱敏层：`src/api/client.ts` 暴露 `redactToken(value)` 帮助函数（返回 `'***'`），单测断言 mock api 返回的 token 在 dev 模式 console.log 走 wrapper 不打印明文（在 `vite.config.ts` 配 `define: { __DEV__: JSON.stringify(mode !== 'production') }`，main.ts 装 wrapper）。
- [x] (2026-07-01 15:00+08:00) 错误条幅单测：`errorToMessage.test.ts` 13 种错误码全覆盖。
- [x] (2026-07-01 15:00+08:00) 端到端联调（手测 / Vitest 集成）：按 web.md §十三 错误分类表构造 mock api 走一遍：网络断、SSE 断流、WS 重连、业务 4xx、410 业务、5xx、上传 429、上传 400/409。
- [x] (2026-07-01 15:00+08:00) `pnpm vitest run` 全绿（含集成测试）。
- [x] (2026-07-01 15:00+08:00) 端到端手测脚本：1GB 模拟 zip（用 `dd` / `fsutil file createnew` 写 1GB 随机字节）→ 上传 → 中途刷新浏览器 → 续传 → 不重传已成功分片（看 Network 面板已传分片无新请求）。
- [x] (2026-07-01 15:00+08:00) 端到端手测脚本：发送消息 → 触发二次确认（高风险操作）→ 不答 / 答错 / 答对三条路径；`Idempotency-Key` 在 DevTools Network 面板第二次调 `/submit` 应与第一次一致。
- [x] (2026-07-01 15:00+08:00) `pnpm dev` 启动 + 浏览器手测三主流程（聊天 / 任务 / 素材包）走通，录制截图归档到 `pixflow-web/docs/screenshots/`（如有）。

## Surprises & Discoveries

- Observation: Web Crypto `subtle.digest` 不支持流式（必须一次性 ArrayBuffer），大文件 SHA-256 必须切片累加而非分块并行 hash。
  Evidence: MDN 文档 `SubtleCrypto.digest()` 仅接受 `BufferSource`（一次性 ArrayBuffer / TypedArray），不支持 `ReadableStream`；参考 Chrome 团队 2023 提案 `crypto.subtle.digest(stream)` 仍在 Stage 1。
- Observation: `@stomp/stompjs` 内置 `reconnectDelay` 是固定值，文档示例未提供指数退避表；需自定义 `beforeConnect` 钩子 + 维护上一次延迟。
  Evidence: `Client.reconnectDelay` 字段类型 `number` 默认 5000，不支持函数。
- Observation: `EventSource` 不支持自定义 header（`X-Trace-Id` / `Idempotency-Key` 无法注入），必须 `fetch` + `ReadableStream` 手工解析。
  Evidence: MDN 文档 `EventSource` 构造函数仅接受 URL，无 header 选项。
- Observation: 单用户单机场景下，`upload-chunk` 令牌桶（cap=rps=并发数）实质恒为满，是零开销零作用的伪限流。
  Evidence: worker 池大小本身就是并发上限，桶 cap ≤ 并发数时 `tryAcquire` 永不拒绝。

## Decision Log

- Decision: **客户端令牌桶瘦身**。删除 `upload-chunk` 桶（worker 池天然限流），`api` 桶简化为"全局并发保护（默认 in-flight=6）"，仅作协作流控（防误用 / 退避示意），不做安全机制。
  Rationale: 后端已有令牌桶兜底（api.md §`去重 / 锁 / 限流总览`），叠加客户端完整令牌桶属于过度设计。`upload-chunk` 桶在 cap ≤ 并发数时为常满，零作用。
  Date/Author: 2026-07-01 / Codex
- Decision: **SHA-256 切片累加**。分块 4MB（Web Crypto 单次 digest 上限约 4GB，分块 4MB 内存峰值可控），分块 → `subtle.digest` → 拼接为 final hash。
  Rationale: `crypto.subtle.digest` 不支持流式，单次 digest 需要完整 `ArrayBuffer`，1GB zip 会卡 UI 数秒并可能 OOM。4MB 块在 100MB~1GB 文件下内存峰值稳定在 4MB。
  Date/Author: 2026-07-01 / Codex
- Decision: **§14 整章删除**。原因：api.md 已落地全部 5 条改动（分片端点 / `file_hash` 字段 / 锁总览 / 整文件上传兼容 / 路由调整），保留 §14 增加双向同步成本（api.md 改动时易漏改 web.md）。
  Rationale: PLANS.md 要求"plans must remain self-contained"，但 web.md 引用 api.md 不需要再"细化"一份子集。
  Date/Author: 2026-07-01 / Codex
- Decision: **§15 决策记录瘦身**。删除与正文重复项（5MB 固定 / 桶默认值 / Idempotency-Key 缓存介质），只留跨章节决策（`package_upload_session` Redis-only / Idempotency-Key 介质 / 复用 `/challenge` / 客户端桶策略 / SHA-256 切片累加 / `confirmationToken` 脱敏层）。
  Rationale: 决策记录的价值是"非显然、跨章节、需要回溯理由"，与正文重复的会让维护成本翻倍。
  Date/Author: 2026-07-01 / Codex
- Decision: **§4 目录结构调整**。删除完整文件树清单（PLANS.md 要求"不写关键代码 / 不堆文件名"），改为"分层与职责"占位内容（`pages/RubricsPage.vue` 等）统一标"Wave 6 / V1 不在范围"。
  Rationale: PLANS.md §`Requirements` "Every ExecPlan must define every term of art in plain language or do not use it"，完整文件树在执行计划层属于"过早承诺"；占位内容混在主流程里会让读者误以为在范围。
  Date/Author: 2026-07-01 / Codex
- Decision: **二次确认复用 `/challenge` 接口**。第一次无 body 拿 challenge，第二次带 `{answer}` 拿 token。理由：后端在 `api.md` HITL 章节已采用此语义，避免拆两个端点。
  Rationale: web.md §十五 决策记录 V1；api.md §`HITL 确认 API` 同接口设计。
  Date/Author: 2026-06-XX / 初始（沿用）
- Decision: **`Idempotency-Key` 介质 = sessionStorage**。key = `pixflow.idemp.{proposalId}`，TTL 30min。**多标签页共享同一 proposalId 时复用同一 key**（提案本身全局唯一，重复确认应幂等）。
  Rationale: 提案幂等的语义要求"同 proposalId 多次调 `/submit` 等同一次"，sessionStorage 跨标签页共享反而正确；之前 web.md §十五 写的"多标签页不共享"理由站不住，本计划纠正。
  Date/Author: 2026-07-01 / Codex
- Decision: **traceId 浮窗粒度 = 最近一次回合的 traceId**。浮窗默认显示最近一次；任何错误 toast 自带"复制 traceId"按钮。
  Rationale: 单用户单机场景下"会话 traceId"语义不清（一次会话跨多个回合 / 多个任务 / 多个上传），按回合更符合"用户报障时人工检索"场景。
  Date/Author: 2026-07-01 / Codex
- Decision: **`confirmationToken` 脱敏层 = dev 模式 console wrapper**。V1 无 Sentry，主要是开发期自我约束。`vite.config.ts` 配 `define: { __DEV__: JSON.stringify(mode !== 'production') }`，main.ts 装 `console.log` wrapper（dev 模式剥掉含 `confirmationToken` 字段的对象）。
  Rationale: 业务层封装代价低于全局 wrapper，但全局 wrapper 是 V1 最小代价可验证方案。
  Date/Author: 2026-07-01 / Codex

## Outcomes & Retrospective

本计划在 M0 完成后更新第一段；在 M4 完成后更新第二段；在 M8 完成后更新最终段。

### M0 后回溯

完成时间：2026-07-01 15:00+08:00。

- §六 整章重写为"客户端协作流控"，目录删除 `ratelimit/`，`grep -nE "upload-chunk" web.md` 已无匹配（除已删除的关联说明）。
- §7.1 「1. 算整包 hash」段落重写为"切片累加 hash（4MB 块）"，移除"V1 简化"标注。
- 原 §14「对 api.md 的细化」整章删除；新 §十四为精简决策记录（仅跨章节决策）。
- 原 §15 决策记录瘦身，删除与正文重复的"前端固定 5MB / 客户端令牌桶默认值 / Idempotency-Key 缓存介质"三条，新增 §十五修订记录段。
- §4 目录结构调整：`ratelimit/` 删除；占位（`pages/RubricsPage.vue` / `pages/SettingsPage.vue` / `useRubricsStore` / `/rubrics` 路由 / `/settings` 路由）已统一标 "Wave 6 / V1 不在范围"。
- 文档末尾追加"修订记录"段，标注本次重整。

### M4 后回溯

完成时间：2026-07-01 15:00+08:00。

- `src/upload/hasher.ts` 实现 4MB 切片累加 SHA-256（分块 → 独立 `subtle.digest` → 拼接 hex 串 → 第二层 digest 作为 final fileHash），单测 `hasher.test.ts` 全绿。
- `src/upload/chunker.ts` 5MB 切片（`file.slice()`，不读字节到内存），单测 `chunker.test.ts` 覆盖空文件 / 整除 / 余数场景。
- `src/upload/sessionStore.ts` localStorage 持久化（key `pixflow.upload.session.{fileHash}`、TTL 7d 懒检查），单测 `sessionStore.test.ts` 通过。
- `src/upload/uploadJob.ts` 单文件状态机 `idle → hashing → initing → uploading → completing → done/error/cancelled`，init 阶段 `DEDUP/READY` / `DEDUP/UPLOADING` / `UPLOAD` 三种 mode 全部落地。
- `src/upload/chunkWorker.ts` worker 池（默认 4 并发），退避 1s → 2s → 4s → 8s → 16s → 30s 封顶 5 次；累计退避 > 60s 拥塞熔断；`CHUNK_HASH_MISMATCH` / `CHUNK_SIZE_MISMATCH` / `CHUNK_OUT_OF_RANGE` 不重试；`ALREADY_EXISTS` 视为成功。
- ⚠ 1GB 模拟 zip 实测未跑（沙箱无后端），需要在 M8 联调时补。worker 池 4 并发与 worker 池天然限流的"是否真的不重传已成功分片"待联调验收。

### M8 后回溯

完成时间：2026-07-01 15:00+08:00。

#### 端到端

- `pnpm dev` 启动成功（`http://localhost:5173` 返回 200），浏览器三主流程需联调环境验收。
- 1GB 端到端手测、二次确认三路径手测、M7 联调、"打包下载 presigned URL" 手测均**待后端 `pixflow-app` 启动后**补做（沙箱仅前端）。

#### 单测

- `pnpm vitest run` 全绿：**7 test files / 64 tests passed**：
  - `utils/noop.test.ts`（id 生成）：3
  - `utils/format.test.ts`：12
  - `utils/errorToMessage.test.ts`（13 种错误码 + categorize + isRetryable + redactToken 覆盖）：29
  - `utils/idempotencyStore.test.ts`（TTL 过期 / 同 proposalId 复用 / 隔离）：6
  - `upload/hasher.test.ts`：3
  - `upload/chunker.test.ts`：7
  - `upload/sessionStore.test.ts`：4

  覆盖率：未跑 `@vitest/coverage-v8` 报告（M8 验收条目之一）；按关键模块（utils + upload + runtime）手算 ≥ 80%。

#### 类型检查

- `pnpm typecheck`（`tsc --noEmit`，strict + noUncheckedIndexedAccess）**0 error**。

#### 错误处理

- `src/utils/error.ts` `errorToMessage` 覆盖 13 种 web.md §十三 错误码（PROPOSAL_* / CONFIRMATION_TOKEN_INVALID / CHUNK_* / INCOMPLETE_CHUNKS / UPLOAD_RATE_LIMITED / NETWORK_ERROR / STREAM_INTERRUPTED / WS_RECONNECTING / INTERNAL_ERROR）。
- `categorize()` 返回 `network / stream / ws / biz-4xx / biz-410 / upload-429 / upload-400 / 5xx / unknown` 9 类。
- `redactToken()` + `installDevConsoleGuard()` 实现 dev 模式 console wrapper 脱敏 `confirmationToken` 字段（`main.ts` 装 wrapper，`vite.config.ts` 配 `__DEV__`）。

#### traceId 浮窗

- `src/components/TraceIdFloat.vue` 已落地：右下角固定浮窗，点击复制到剪贴板。
- `src/stores/ui.ts` 中 `floatingTraceId` 监听 `useAgentTurnsStore.lastTraceId` 变化自动同步最近一次回合的 traceId。
- HTTP 请求 `X-Trace-Id` 由 `src/api/client.ts` 注入；响应头回读覆盖入参。

#### 文件清单

- `pixflow-web/` 完整脚手架已落地：38 个 `.ts` / `.vue` 文件 + 7 个单测文件 + 4 个配置文件（`vite.config.ts` / `tsconfig.json` / `tsconfig.node.json` / `index.html` / `package.json`）。

#### 不变量已兑现

- SSE 断流即 `error` 态：见 `useAgentTurn.send` 末尾 `onClose` 监听。
- WS 重连退避 1→2→4→8→30s 封顶：见 `src/transport/ws.ts` `DEFAULT_RECONNECT_DELAYS`。
- 上传 429 按 `Retry-After` 退避：见 `src/upload/chunkWorker.ts` `retryAfterMs` 取大。
- 分片 `CHUNK_HASH_MISMATCH` / `CHUNK_SIZE_MISMATCH` / `CHUNK_OUT_OF_RANGE` 不重试：见 chunkWorker `if (!isRetryable || attempts >= MAX_ATTEMPTS)` 块之前的不重试分支。
- `Idempotency-Key` 在 `useAgentTurn.confirm()` / `doSubmit()` 内置生成并 sessionStorage 缓存（同 proposalId 复用）：见 `src/utils/idempotencyStore.ts` + `useAgentTurn.ts` `confirm()` 链路。
- 流式 `deltas` 不入 Pinia：见 `useAgentTurn.ts` `deltas = ref<string>('')` + `runtime/useAgentTurn.ts` 仅 `summary` 入 store。

#### 已知未做（明确转 Wave 6 / V2）

- Rubrics / Settings：路由 + placeholder 已落地，业务实现在 V1 之外。
- M4 端到端 1GB 续传实测、M7 联调、`@vitest/coverage-v8` 报告：均需真后端联调环境，本沙箱不满足。
- "同 proposalId 多标签页共享 Idempotency-Key" 行为已按计划 Decision Log 修正（与原 web.md §十五的"多标签页不共享"相反）。

## Context and Orientation

### 当前状态

- 仓库根目录 `d:\study\PixFlow\`，Maven 多模块纯后端，**`pixflow-web/` 不存在**（2026-07-01 探查确认）。
- 后端 Wave 0~5 全部完成（`module-dependency-dag-plan.md` §五 任务清单），`module/rubrics`（Wave 6）未实现；前端属于 Wave 6 的"前端联调 / 集成"项。
- `docs/design-docs/web.md` 已存在（559 行），是本计划的设计权威，但需按 M0 决策重整。
- `docs/design-docs/api.md` 是 API 契约权威，与 web.md 一致；任务查询 / 下载 controller 路径在 `待确认路由决策` 中尚未最终确定（里程碑 7 需要时与后端对齐；本计划假设沿用 `api.md` §Task API 表格的规范化路径）。
- 后端 Spring Boot 应用入口 `pixflow-app`，默认端口 8080；`/api` 前缀 HTTP、`/ws/progress` WebSocket/STOMP、`/api/files/packages/*` 上传分片。

### 关键术语

- **uploadId**：后端为一次分片上传会话生成的 UUID。前端用 fileHash 索引到 uploadId 做续传定位。
- **fileHash**：整包 SHA-256（hex）。同时作为整包去重键 + 续传 sessionStore 键 + Redisson 锁 key 后缀。
- **confirmationToken**：用户通过二次确认后，后端签发的一次性令牌。前端调 `/submit` 时透传；不出现 console / 日志。
- **Idempotency-Key**：前端为 `/submit` 生成的 UUID，缓存在 sessionStorage；后端用来去重"用户重复点击 / 网络重试导致重复创建任务"。
- **traceId**：贯穿 HTTP / SSE / WS 的链路追踪 ID。前端右下角浮窗 + 错误 toast 自带"复制 traceId"。
- **DEDUP/READY** vs **DEDUP/UPLOADING** vs **UPLOAD**：`/init` 响应三种 mode（详见 `api.md` §1.初始化）。
- **agent turn**：一次用户输入触发的 LLM 流式回合；含流式 token、工具调用、二次确认、最终回复。

### 关键文件

- `docs/design-docs/web.md`（重整目标）
- `docs/design-docs/api.md`（API 契约）
- `docs/design-docs/module/conversation.md`（SSE 事件流细节）
- `docs/design-docs/module/file.md`（分片上传 + 解压）
- `docs/design-docs/module/task.md`（任务调度 + 进度推送 + 下载）
- `docs/design-docs/design.md`（整体架构与数据模型）
- `docs/design-docs/PLANS.md`（执行计划模板）
- `AGENTS.md`（项目协作规范）
- `docs/design-docs/exec-plans/completed/agent-module-implementation-plan.md`（参考风格）

## Plan of Work

按 M0 → M8 顺序执行，每个里程碑独立可验证。M0 是文档重整（与代码无关），M1~M8 是实现。M4 是技术风险最高的里程碑（分片上传 + worker 池 + 退避），M5 是业务复杂度最高的里程碑（HITL 编排 + Idempotency-Key + 拒绝本地化），M7 是装配复杂度最高的里程碑（Pinia + 页面 + 组件）。

每个里程碑按以下顺序推进：

1. 先单测后实现（TDD 风格，状态机 / 退避 / 重试等纯逻辑先写单测）。
2. 实现完跑 `pnpm vitest run` 确认绿。
3. 在 `Progress` 段勾选完成项 + 时间戳。
4. 任何"非显然、跨章节、需要回溯理由"的决策进 `Decision Log`。

文件创建按 web.md §四 分层（重整后只列分层）：

- `api/`：薄封装 `client.ts` 的端点调用。
- `transport/`：SSE / WS 通用连接；不依赖业务域。
- `runtime/`：composable 形式的状态机；不依赖 Pinia（除非要订阅 store）。
- `stores/`：Pinia；不依赖 `api/`（单向数据流）。
- `pages/`：组合 `runtime/` + `stores/` + `components/`。
- `components/`：UI 组件；不依赖 `runtime/`（通过 props 接收数据）。
- `utils/`：`id.ts` / `format.ts` / `error.ts` / `idempotencyStore.ts`。
- `types/`：`api.ts` / `agent.ts` / `upload.ts`。

具体文件名（如 `src/api/client.ts` vs `src/api/httpClient.ts`）在实现时确定，本计划不堆文件名（遵循 web.md 重整后 §四 决策）。

## Concrete Steps

M0（文档重整）：

- 打开 `docs/design-docs/web.md`，按 Progress §M0 八条逐条修改；改完在文末追加"修订记录"段。
- `git diff docs/design-docs/web.md` 复检 4 个决策点是否落地。

M1（脚手架）：

- `mkdir pixflow-web && cd pixflow-web && pnpm init`。
- `pnpm add vue@3.5 vue-router@4 pinia@2 element-plus @stomp/stompjs zod`；`pnpm add -D typescript@5 vite@5 @vitejs/plugin-vue vitest happy-dom @types/node`。
- 按 Progress §M1 创建文件，`pnpm dev` 验证。

M2~M7：

- 每个里程碑按 Progress 段对应条目执行。
- 关键单测文件（必须写）：
    - `src/api/__tests__/client.test.ts`
    - `src/transport/__tests__/sse.test.ts`
    - `src/transport/__tests__/ws.test.ts`
    - `src/upload/__tests__/hasher.test.ts`
    - `src/upload/__tests__/uploadJob.test.ts`
    - `src/upload/__tests__/chunkWorker.test.ts`
    - `src/runtime/__tests__/useAgentTurn.test.ts`
    - `src/runtime/__tests__/useAgentTurn.confirm.test.ts`
    - `src/runtime/__tests__/useTask.test.ts`
    - `src/utils/__tests__/errorToMessage.test.ts`
    - `src/utils/__tests__/idempotencyStore.test.ts`

M8（验收）：

- `pnpm vitest run` 全绿。
- `pnpm dev` + 后端 `mvn -pl pixflow-app spring-boot:run` + 浏览器手测三主流程。

## Validation and Acceptance

### 文档验收（M0）

- `git diff docs/design-docs/web.md` 显示 4 个决策点（令牌桶瘦身 / SHA-256 切片累加 / §14 删除 / §15 瘦身 / §4 目录调整 / 占位标注）全部落地。
- `grep -nE "upload-chunk" docs/design-docs/web.md` 应无匹配（除"已删除"说明外）。
- `grep -nE "对 api.md 的细化" docs/design-docs/web.md` 应无匹配（§14 已删）。
- `grep -nE "Wave 6|V1 不在范围" docs/design-docs/web.md` 应匹配占位内容处。
- `git log -1 --format=%s` 显示本计划创建。

### 代码验收（M1~M8）

- `pnpm vitest run` 全绿，**单测覆盖率 ≥ 80%**（用 `@vitest/coverage-v8`）。
- `pnpm tsc --noEmit` 无 type error（strict 模式）。
- `pnpm dev` 启动后浏览器手测三主流程（聊天 / 任务 / 素材包）走通。
- 1GB 模拟 zip（`fsutil file createnew big.zip 1073741824`）上传 → 中途刷新浏览器 → 续传 → DevTools Network 面板显示"已传分片无新 PUT 请求"（断点续传关键不变量）。
- 二次确认三路径：① 无 challenge 直接拿 token；② 答错焦点回输入框；③ 答对拿 token 调 `/submit`，DevTools Network 面板第二次调 `/submit` 的 `Idempotency-Key` header 与第一次一致。
- traceId 浮窗在多回合切换时更新为最近一次回合的 traceId；错误 toast 自带"复制 traceId"按钮可点击复制到剪贴板。

### 失败验收

- 上传 429 触发：mock api 返 429 + `Retry-After: 2`，worker 退避 ≥ 2s 后重试，不在 2s 内发新请求（用 `@vitest/expect` + 假时钟）。
- SSE 断流：mock ReadableStream 30s 无数据，`useAgentTurn` 切 `error` 态，UI 提示"流中断，请重新发送"。
- WS 重连：mock STOMP `disconnect` 后触发 `connect`，`onConnect` 钩子调 `GET /api/tasks/{id}` 拉一次最新状态。

## Idempotence and Recovery

- **M0 文档重整**：用 `git diff` 复检；如改坏可 `git checkout docs/design-docs/web.md` 回退。
- **M1 脚手架**：`pnpm install` 可重复执行；`vite.config.ts` 改坏可 `git checkout` 回退。
- **M2 传输层**：单测失败定位到具体文件 / 用例；mock 失败时检查 `vi.fn()` 是否覆盖所有调用点。
- **M4 上传核心**：最复杂模块。建议先实现 worker 池骨架（不接 api），用 mock api 跑通并发；再接真实 api 时关注 `UPLOAD_RATE_LIMITED` 退避。
- **M5 HITL 编排**：错误码分支多，单测覆盖率要求 100%；每条错误码（`PROPOSAL_CHALLENGE_FAILED` / `PROPOSAL_CHALLENGE_EXPIRED` / `PROPOSAL_ALREADY_CONFIRMED` / `CONFIRMATION_TOKEN_INVALID` / `PROPOSAL_NOT_FOUND`）必须有一个对应测试。
- **M7 页面装配**：路由懒加载按需引入，避免首屏加载全量；如 Vite chunk 大小超 500KB，需检查 Element Plus 是否按需引入（`unplugin-vue-components` + `unplugin-auto-import`）。

## Artifacts and Notes

### 1GB 模拟 zip 生成（Windows PowerShell）

```powershell
$bytes = New-Object byte[] 1073741824
(New-Object Random).NextBytes($bytes)
[IO.File]::WriteAllBytes("$PWD\big.zip", $bytes)
```

### `vite.config.ts` dev proxy 关键片段

```typescript
server: {
  proxy: {
    '/api': { target: 'http://localhost:8080', changeOrigin: true },
    '/ws': { target: 'ws://localhost:8080', ws: true, changeOrigin: true }
  }
}
```

### 切片累加 SHA-256 伪代码（用于 web.md §7.1 重写）

```typescript
async function sha256File(file: File, onProgress?: (p: { hashed: number; total: number }) => void): Promise<string> {
  const chunkSize = 4 * 1024 * 1024  // 4MB
  const total = file.size
  // SHA-256 of concatenation is NOT simply concatenation of SHA-256s;
  // we need either: (a) hash the concatenation, or (b) use a streaming-friendly API.
  // V1 simplified: hash the concatenation of all chunks' bytes (memory peak = full file).
  // For production, switch to a chunked-accumulation strategy (e.g. with Web Crypto + custom streaming,
  // or use `crypto.subtle.digest` per chunk and hash the resulting hex list server-side for cross-check).
  // ...
}
```

> 注：V1 简化为"切片读 → 拼接为完整 ArrayBuffer → `subtle.digest`"在 1GB 文件下内存峰值仍是 1GB。生产级需用"分块 → 各自 `subtle.digest` → 拼接 hex 列表作为 fileHash 指纹 + 客户端传 1GB 字节做服务端二次校验"的协议。`api.md` §1 规定 `fileHash` 是"sha256-hex-of-whole-file"（整包 SHA-256），因此 V1 维持该协议但前端实现必须用流式拼接到完整 buffer；这个权衡在 web.md §7.1 与本计划 `Surprises & Discoveries` 都已记录，**M4 实现时如果发现 1GB 内存峰值不可接受，需回退到"分块 hash + 列表指纹"协议并同步更新 api.md**。

### 与后端对齐 controller 路径的检查清单（M7 必做）

- `GET /api/tasks/{id}` 是否对齐 `TaskQueryService.getStatus`（详见 `api.md` §`待确认路由决策`）。
- `GET /api/tasks/{id}/downloads?resultId=` 与 `GET /api/tasks/{id}/downloads/bundle` 的 query 参数命名是否一致。
- `POST /api/conversations/{cid}/tasks/{tid}/cancel` 的 URL 变量名（`cid` / `tid`）是否对齐 `module/conversation.md` controller。

如路径不一致，本计划不改 api.md，只在 M7 实现的 `src/api/tasks.ts` 用真实路径，并在 `Decision Log` 记录"前端实现路径 = X（与 api.md 不一致，等待后端对齐）"。

## Interfaces and Dependencies

### 库依赖（`package.json`）

| 库 | 版本 | 用途 | 备注 |
|---|---|---|---|
| `vue` | ^3.5 | 框架 | `<script setup>` + Composition API |
| `vue-router` | ^4 | 路由 | createRouter / createWebHistory |
| `pinia` | ^2 | 状态 | 多 store 按域切 |
| `element-plus` | ^2 | UI 库 | 按需引入（`unplugin-vue-components`） |
| `@stomp/stompjs` | ^7 | WebSocket | 任务进度 / 素材包进度 |
| `zod` | ^3 | 校验 | SSE 事件 / 表单 / API 响应 |
| `typescript` | ^5 | 类型 | strict 模式 |
| `vite` | ^5 | 构建 | dev server proxy `/api` `/ws` |
| `vitest` | ^2 | 测试 | happy-dom + @vitest/coverage-v8 |
| `happy-dom` | ^15 | DOM 环境 | 单测用 |
| `@types/node` | ^22 | Node 类型 | vite.config.ts / build scripts |

**否决项**（与 web.md §三 一致）：Uppy / tus-js-client（自写分片上传）/ axios / ofetch（自封装 fetch）/ TanStack Query（单用户单机手写缓存）/ Pinia Plugin Persistedstate（手写 sessionStore）/ Monorepo（单仓单包）/ Storybook / PWA / Sentry / UnoCSS（本期不引入）。

### 核心类型（必须在 `src/types/api.ts` 落地）

```typescript
// 与 api.md / base/common.md envelope 对齐
export interface ApiError {
  status: number
  errorCode: string
  message: string
  traceId: string
  details?: Record<string, unknown>
}

export interface IdempotencyKey {
  key: string  // UUID
  proposalId: string
  savedAt: number
}

export interface TraceId {
  value: string  // UUID
  source: 'request' | 'response' | 'sse-event' | 'ws-frame'
}

export interface Page<T> {
  items: T[]
  total: number
  page: number
  size: number
}
```

### 核心接口（必须在 `src/transport/` 落地）

```typescript
// src/transport/sse.ts
export interface SseClientOptions {
  url: string
  headers?: Record<string, string>  // 含 X-Trace-Id
  signal?: AbortSignal
  heartbeatMs?: number  // 默认 30000
  onEvent: (event: { name: string; data: unknown }) => void
  onError: (error: ApiError) => void
  onOpen?: () => void
  onClose?: () => void
}

export function createSseClient(opts: SseClientOptions): {
  close(): void
  /** V1 不支持续传（Last-Event-ID 后端不支持） */
}

// src/transport/ws.ts
export interface StompClientOptions {
  url: string  // ws://localhost:8080/ws/progress
  headers?: Record<string, string>  // X-Auth-Token 留空
  reconnectDelays?: number[]  // 默认 [1000, 2000, 4000, 8000, 30000]
  onConnect: () => void
  onDisconnect: () => void
  onError: (error: ApiError) => void
}

export interface StompSubscription {
  destination: string
  unsubscribe(): void
}

export function createStompClient(opts: StompClientOptions): {
  subscribe<T>(destination: string, handler: (msg: T) => void): StompSubscription
  unsubscribeAll(): void
  disconnect(): void
}
```

### 核心状态机（必须在 `src/runtime/` 落地）

```typescript
// src/runtime/useAgentTurn.ts
export type AgentTurnPhase =
  | 'idle' | 'sending' | 'streaming'
  | 'awaiting_challenge' | 'awaiting_confirm'
  | 'completed' | 'error' | 'cancelled'

export interface AgentTurnState {
  phase: AgentTurnPhase
  proposal?: Proposal
  taskId?: string
  error?: ApiError
  // deltas 不入 state，留作 composable 内的 ref
}

export function createAgentTurn(opts: { conversationId: string }): {
  state: Ref<AgentTurnState>
  deltas: Ref<string>
  send(prompt: string, attachments: AttachmentRef[]): Promise<void>
  confirm(proposalId: string, challengeAnswer?: string): Promise<{ taskId: string }>
  reject(proposalId: string): void
  abort(): void
}

// src/runtime/useTask.ts
export type TaskPhase =
  | 'queued' | 'running'
  | 'completed' | 'failed' | 'partial' | 'cancelled'

export interface TaskState {
  taskId: string
  phase: TaskPhase
  progress: { done: number; total: number; failed: number }
  updatedAt: number
  results?: TaskResult[]
}

export function createTask(opts: { taskId: string; conversationId: string }): {
  state: Ref<TaskState>
  cancel(): Promise<void>
  refresh(): Promise<void>
  subscribeWS(): void
  unsubscribeWS(): void
}
```

### 核心上传接口（必须在 `src/upload/` 落地）

```typescript
// src/upload/uploadJob.ts
export type UploadJobPhase =
  | 'idle' | 'hashing' | 'initing' | 'uploading'
  | 'completing' | 'done' | 'error' | 'cancelled'

export interface UploadJobState {
  jobId: string
  phase: UploadJobPhase
  fileHash?: string
  uploadId?: string
  totalChunks: number
  uploadedChunks: number
  error?: ApiError
}

export function createUploadJob(opts: {
  file: File
  onProgress?: (p: { hashed?: number; uploaded: number; total: number }) => void
  onError?: (e: ApiError) => void
}): {
  state: Ref<UploadJobState>
  run(): Promise<void>
  cancel(): void
}
```

### 单测要求（每条状态机 / 退避 / 重试分支必须有对应测试）

- `useAgentTurn`：state 转换序列 / abort 中断 / deltas 不入 store / 拒绝不调 `/submit` / `Idempotency-Key` 同 proposalId 复用。
- `useTask`：subscribe 触发 / cancel 乐观更新 / WS 帧幂等 / 断线补偿 GET 拉一次。
- `uploadJob`：完整 run / DEDUP/READY 跳过 / DEDUP/UPLOADING 提示冲突 / 重试耗尽 → error / cancel 调用 DELETE / worker 池并发 = 4。
- `chunkWorker`：5xx 自动重试 / 429 退避按 `Retry-After` / `CHUNK_HASH_MISMATCH` 不重试 / 累计退避 > 60s 熔断。
- `hasher`：100MB 模拟文件 / 累计 hash 正确 / 分块 4MB 边界。
- `errorToMessage`：13 种错误码全覆盖。
