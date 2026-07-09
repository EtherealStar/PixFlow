# 前后端契约与设计文档对齐

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划按仓库根目录的 `PLANS.md` 维护。本文是自包含的中文执行计划：后续执行者只需要当前工作树和本文，就能理解为什么要做、改哪些文件、按什么顺序实现、如何验证行为。执行本计划前仍应按 `AGENTS.md` 先阅读 `docs/design-docs/exec-plans/` 中当前执行计划和 `docs/design-docs/index.md`。

## Purpose / Big Picture

当前前端和后端都能启动，但主页点击“新对话”或从主页发送新消息后，页面没有正确进入可用对话，也没有按设计调用后端接口。根因不是单个按钮失效，而是前后端契约有多处不一致：前端按 `/api/conversations/...`、`/ws/progress`、分片上传和带鉴权的传输层设计实现，后端部分 controller 仍暴露无 `/api` 前缀的会话路由、WebSocket 端点是 `/ws`，文件模块还没有分片上传端点，SSE 和任务状态 payload 也未完全按 `api.md` 输出。

完成本计划后，用户从主页输入 prompt 并提交时，前端会创建真实会话、跳转到 `/chat/{conversationId}`、向 `/api/conversations/{conversationId}/messages` 发起 SSE 回合请求，并能收到符合前端 schema 的 `assistant_delta` 与 `completed` 事件；用户上传素材包时，前端使用设计中的 `init -> chunks -> complete` 分片协议，后端完成 Redis 会话、MinIO 分片合并和素材包入库；任务和素材包进度通过 `/ws/progress` 推送；HTTP、SSE、STOMP 都携带同一套 `Authorization: Bearer <accessToken>`。附件不做第二套上传存储，V1 沿用素材包上传结果，通过 `PACKAGE_REFERENCE` 或已上传文件引用进入对话上下文。

## Progress

- [x] (2026-07-06 10:30+08:00) 阅读 `PLANS.md`，确认本计划必须自包含，并包含 `Progress`、`Surprises & Discoveries`、`Decision Log`、`Outcomes & Retrospective` 等章节。
- [x] (2026-07-06 10:35+08:00) 阅读当前执行计划，确认 `files-page-backend-api-plan.md` 已补齐 `/files` 页面素材图片、产物聚合、删除、重命名和自定义下载的主要接口，本计划不重复该范围。
- [x] (2026-07-06 10:45+08:00) 阅读 `docs/design-docs/index.md`、`api.md`、`module/file.md`、`module/conversation.md`、`module/task.md`、`infra/auth.md` 和 `frontend/` 相关文档，确认前后端主契约以设计文档为准。
- [x] (2026-07-06 11:00+08:00) 对照当前代码定位主要缺口：会话路由前缀、鉴权注入、SSE payload、WebSocket endpoint、任务状态 DTO、分片上传后端端点、附件语义和 `/chat/new?q=` 生命周期。
- [x] (2026-07-06 11:20+08:00) 新建本执行计划，明确后续实现机制、顺序、验收方式和可快速查询设计依据的关键词。
- [x] (2026-07-06 11:55+08:00) 实现真实前端鉴权登录、HTTP/SSE/STOMP token 注入。
- [x] (2026-07-06 12:20+08:00) 对齐 conversation REST/SSE 路由和 `/chat/new?q=` 自动发送流程。
- [x] (2026-07-06 13:20+08:00) 实现 file 模块后端分片上传协议，不再只依赖整文件 multipart 兼容端点。
- [x] (2026-07-06 13:45+08:00) 对齐任务状态 REST payload、任务/素材包 WebSocket 进度端点和 topics。
- [x] (2026-07-06 13:55+08:00) 补齐 file 自动装配测试所需的 cache/lock 测试端口，验证分片上传服务可随 FileController 一起创建。
- [x] (2026-07-06 14:05+08:00) 修正 fileHash 上传会话复用和 complete 后 READY 会话保留窗口，避免同 hash 并发 init 重复创建会话。
- [ ] 实现契约对齐前的后端 controller 路由与传输层契约测试。
- [ ] 将附件入口改为复用素材包/文件上传结果的绑定语义，避免独立存储通道。
- [x] (2026-07-06 14:15+08:00) 完成模块级自动验证并更新本文 `Outcomes & Retrospective`。
- [ ] 完成浏览器端到端手工验证。

## Surprises & Discoveries

- Observation: 分片上传不是文档空白。`api.md` 和 `module/file.md` 已明确要求 `POST /api/files/packages/init`、`PUT /api/files/packages/sessions/{uploadId}/chunks/{index}`、`POST /api/files/packages/sessions/{uploadId}/complete`、`DELETE /api/files/packages/sessions/{uploadId}`，并写明 Redis-only 会话、Redisson 锁、MinIO `composeObject`、SHA-256 整包去重。
  Evidence: 在 `docs/design-docs/api.md` 搜索 `V1 采用分片上传协议`、`分片上传协议`、`lock:package-upload`、`composeObject`；在 `docs/design-docs/module/file.md` 搜索 `五、分片上传与整包去重`。

- Observation: 前端上传状态机已经按分片协议调用后端，但当前后端 `FileController` 仍主要暴露 `POST /api/files/packages` 整文件 multipart 兼容端点，未暴露 `init/chunks/complete/session delete` 这组端点。
  Evidence: `pixflow-web/src/api/packages.ts` 调用 `/api/files/packages/init`、`/sessions/{uploadId}/chunks/{index}`、`/complete`；`pixflow-module-file/src/main/java/com/pixflow/module/file/web/FileController.java` 目前没有这些映射。

- Observation: 附件设计有两层表达，必须选一种与当前产品阶段一致的落地方式。`module/conversation.md` 描述 V1 支持 `UPLOAD_IMAGE` 和 `PACKAGE_REFERENCE`，但同一文档修订记录强调“附件统一走素材包”。结合当前用户要求，本计划选择：附件上传沿用原素材包/文件上传，不新增 conversation 私有上传存储；conversation 只负责把已上传素材包或文件引用绑定到消息。
  Evidence: 在 `docs/design-docs/module/conversation.md` 搜索 `附件瘦版`、`UPLOAD_IMAGE`、`PACKAGE_REFERENCE`、`附件统一走素材包`。

- Observation: 主页新对话失败首先会撞上路由契约。前端 `api/conversations.ts` 和 `useAgentTurn.ts` 调用 `/api/conversations...`，但 conversation 模块 controller 当前映射是 `/conversations...`，没有 `/api` 前缀。
  Evidence: `pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/ConversationController.java`、`MessageController.java`、`HistoryController.java`、`ConfirmationController.java`、`CancellationController.java` 的 mapping；`pixflow-web/src/api/conversations.ts`、`pixflow-web/src/runtime/useAgentTurn.ts` 的调用路径。

- Observation: `api.md` 要求 `WS /ws/progress`，前端也连接 `/ws/progress`，但后端 `ProgressMessagingConfig` 当前注册 `/ws`。
  Evidence: 在 `docs/design-docs/api.md` 搜索 `WS /ws/progress`；查看 `pixflow-app/src/main/java/com/pixflow/app/progress/ProgressMessagingConfig.java`。

- Observation: `api.md` 的任务状态 payload 要求嵌套 `progress: { done, total, failed }` 和 `createdAt/startedAt/finishedAt`，当前 `TaskStatusView` 是扁平字段 `total/done/failed/skipped/lastError/createdAt/updatedAt`，前后端需要统一。
  Evidence: 在 `docs/design-docs/api.md` 搜索 `任务状态 payload`；查看 `pixflow-module-task/src/main/java/com/pixflow/module/task/api/query/TaskStatusView.java` 和 `pixflow-web/src/api/tasks.ts`。

- Observation: `api.md` 的 SSE 事件 payload 是按事件类型展开的顶层字段，例如 `assistant_delta` 是 `{ "text": "..." }`，`completed` 是 `{ "finalText": "...", "traceId": "...", "turnNo": 42 }`；当前后端 `SseAgentEventSink` 对所有事件发送通用 `{ type, text, payload, metadata }`，前端 `useAgentTurn.ts` 已按设计 schema 校验。
  Evidence: 在 `docs/design-docs/api.md` 搜索 `assistant_delta`、`completed`；查看 `pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/SseAgentEventSink.java` 和 `pixflow-web/src/runtime/useAgentTurn.ts`。

- Observation: file 自动装配测试暴露了新增分片上传服务的端口依赖：`FileController` 现在依赖 `UploadSessionService`，而 `UploadSessionService` 依赖 `CacheStore`、`CacheNamespace` 和 `LockTemplate`。测试环境需要提供轻量内存端口，不能让单元装配测试依赖真实 Redis。
  Evidence: `pixflow-module-file/src/test/java/com/pixflow/module/file/config/FileAutoConfigurationTest.java` 已提供内存 `CacheStore`、`DefaultCacheNamespace` 和直接执行的 `LockTemplate`。

- Observation: 初始分片上传实现 complete 后立即删除 Redis session，导致前端如果马上 `GET /sessions/{uploadId}` 无法看到 `READY` 状态；这与计划中的 READY 查询窗口不一致。
  Evidence: `UploadSessionService.complete(...)` 现在只清理临时 chunk 和 fileHash active 索引，保留 READY session 到 TTL；取消会话仍删除 session。

## Decision Log

- Decision: 以 `docs/design-docs/api.md`、`module/*`、`infra/auth.md` 和 `frontend/*` 作为前后端契约来源；当当前代码与文档冲突时，优先把代码对齐文档，而不是让前端继续兼容错误路径。
  Rationale: 当前故障来自前后端各自“能跑”但契约不一致。继续加兼容分支会掩盖问题，后续新页面仍会踩同样的坑。
  Date/Author: 2026-07-06 / Codex

- Decision: 会话 REST/SSE 路由统一迁移到 `/api/conversations...`；如担心已有本地脚本，可短期保留无 `/api` 兼容映射，但验收和文档只认 `/api`。
  Rationale: `api.md` 明确所有外部 HTTP 路由使用 `/api` 前缀，前端已经按此前缀实现。修后端 controller 比让前端改回非标准路径更符合全局约定。
  Date/Author: 2026-07-06 / Codex

- Decision: 分片上传是必须落地的后端缺口，整文件 multipart `POST /api/files/packages` 只作为兼容端点保留。
  Rationale: `api.md` 和 `module/file.md` 已经把大文件弱网、断点续传、整包去重和同 hash 互斥作为核心设计。前端也已按分片状态机实现，不能要求前端退回整文件上传。
  Date/Author: 2026-07-06 / Codex

- Decision: 附件不建第二套上传/存储机制。V1 对话附件通过素材包引用或已上传文件引用进入消息；如需要单图附件，底层也复用 file 模块的对象存储和元数据服务，只在 conversation 保存轻量绑定。
  Rationale: 用户明确要求“附件上传就是沿用原本的文件上传”。`module/conversation.md` 也有“附件统一走素材包”的修订记录。单独在 conversation 写 MinIO 私有附件会造成重复存储、重复鉴权、重复清理和后续引用检查困难。
  Date/Author: 2026-07-06 / Codex

- Decision: HTTP、SSE 和 STOMP 都使用 `Authorization: Bearer <accessToken>`；前端 `stores/auth.ts` 不能继续使用假 token，必须调用 `/api/auth/*`。
  Rationale: `infra/auth.md` 已定义 API/STOMP 鉴权，`web.md` 要求 HTTP 注入。没有真实 token 注入时，即使路径修正，后端启用鉴权后仍会失败。
  Date/Author: 2026-07-06 / Codex

- Decision: SSE 与任务状态 payload 优先由后端 outward DTO 适配到 `api.md`，前端只保留薄 adapter，不把后端内部 DTO 泄漏到页面状态机。
  Rationale: 前端 runtime 已以设计 schema 驱动，且 SSE/任务状态是外部契约。让后端输出契约字段能减少页面和测试里的特殊判断。
  Date/Author: 2026-07-06 / Codex

- Decision: `/files` 页面素材/产物图库相关接口不纳入本计划主范围，只把它作为已完成前置条件引用。
  Rationale: `files-page-backend-api-plan.md` 已完成该范围。重复规划会让后续执行者混淆“主页对话/上传契约对齐”和“文件页图库”两个目标。
  Date/Author: 2026-07-06 / Codex

- Decision: 分片合并当前使用 `SequenceInputStream` 串联临时分片写入最终 `source.zip`，不在本轮扩展 `ObjectStorage` 增加 MinIO server-side compose 抽象。
  Rationale: 现有 `ObjectStorage` 接口没有 compose 原语；为了保持本轮变更范围集中，先通过 5MB 分片串流合并满足契约和测试。后续如果上传大文件性能成为瓶颈，应单独把 compose 能力加到 storage 抽象并替换这里。
  Date/Author: 2026-07-06 / Codex

## Outcomes & Retrospective

已完成本轮前后端契约对齐的代码实施。会话 controller 已统一加 `/api` 前缀，SSE outward payload 已从通用 envelope 改为按事件类型投影，`/chat/new?q=` 会先创建真实会话、替换 URL，再执行一次自动发送。WebSocket endpoint 已从 `/ws` 改为 `/ws/progress`。前端 auth store 已接入 `/api/auth/*`，HTTP、fetch SSE 和 STOMP CONNECT 均注入 `Authorization: Bearer <accessToken>`。

file 模块已新增分片上传后端协议：`init`、`get session`、`put chunk`、`complete`、`cancel`。上传会话存储在 cache，按 fileHash 加 active upload 索引；重复 init 会返回 READY 去重包或既有 UPLOADING 会话；chunk 写入临时对象并校验 SHA-256；complete 校验整包 hash，创建素材包，写最终 `source.zip`，发布解压消息，并保留 READY session 到 TTL。整文件 multipart 端点仍保留为兼容入口。

任务状态 REST DTO 已改为嵌套 `progress: { done, total, failed }`，并补齐 `createdAt`、`startedAt`、`finishedAt`。模块级验证已通过：

    mvn -pl pixflow-conversation -am -DskipTests compile
    mvn -pl pixflow-module-file -am test
    mvn -pl pixflow-module-task -am test
    mvn -pl pixflow-app -am -DskipTests compile
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test

保留风险：本轮没有新增完整 controller/MockMvc 契约测试，也没有在真实浏览器中完成“登录 -> 主页 prompt -> SSE 回复 -> zip 分片上传 -> WS 进度”的端到端手工验收。分片合并暂未使用 MinIO server-side compose，而是通过应用层串流合并。

## Context and Orientation

本仓库是 Maven 多模块 Spring Boot 后端加 Vue 3 前端。后端模块中，`pixflow-conversation` 负责会话 REST、消息 SSE、确认和取消入口；`pixflow-module-file` 负责素材包上传、解压、素材图片元数据和对象存储 key；`pixflow-module-task` 负责任务状态、任务结果和下载；`pixflow-app` 是应用装配层，包含 auth controller、WebSocket 配置以及部分 app-level controller。前端位于 `pixflow-web`，使用 Vue Router、Pinia、fetch、ReadableStream SSE 和 STOMP WebSocket。

本文使用几个术语。契约是指前端和后端共同承诺的路径、方法、请求字段、响应字段、事件名和错误语义。SSE 是浏览器通过 HTTP 流持续接收服务端事件的机制，本项目用于 Agent 回合输出。STOMP 是 WebSocket 上的消息协议，本项目用于任务和素材包进度。分片上传是前端把大 zip 切成多个 chunk，后端按 `uploadId` 接收、校验、合并，再创建素材包。附件绑定是 conversation 只保存“这条消息引用了哪些已上传素材/文件”，而不是自己再实现一套上传和对象存储。

当前最相关的文件如下。

`pixflow-web/src/pages/HomePage.vue` 在主页提交 prompt 后跳转到 `/chat/new?q=...`。`pixflow-web/src/pages/ChatPage.vue` 负责在 `cid=new` 时创建会话，再 `router.replace` 到真实 `/chat/{conversationId}`，如果 query 中有 `q`，应在会话就绪后自动发送。`pixflow-web/src/api/conversations.ts` 调用 `/api/conversations`，`pixflow-web/src/runtime/useAgentTurn.ts` 调用 `/api/conversations/{conversationId}/messages` 并按 SSE schema 处理事件。

`pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/ConversationController.java`、`MessageController.java`、`HistoryController.java`、`ConfirmationController.java`、`CancellationController.java` 当前暴露 `/conversations...`。这些路由应迁移到 `/api/conversations...`。`SseAgentEventSink.java` 当前发送通用事件 envelope，应输出 `api.md` 中按事件类型定义的 payload。

`pixflow-web/src/api/packages.ts` 和 `pixflow-web/src/upload/uploadJob.ts` 已经按分片上传流程调用 `initUpload`、`uploadChunk`、`completeUpload`、`deleteSession`。`pixflow-module-file/src/main/java/com/pixflow/module/file/web/FileController.java` 当前有整文件 multipart 兼容上传和素材图片图库接口，但没有分片上传所需的 `init/chunks/complete/session` endpoints。后端实现应落在 file 模块的 upload 包，使用 Redis 会话、Redisson 锁和 MinIO 合并能力。

`pixflow-web/src/api/client.ts` 是 HTTP 统一入口，应注入 `Authorization`。`pixflow-web/src/transport/sse.ts` 是 SSE 入口，也应注入 `Authorization`。`pixflow-web/src/transport/ws.ts` 当前发送空的 `X-Auth-Token`，应改为 STOMP `CONNECT` header 中的 `Authorization: Bearer <accessToken>`。`pixflow-web/src/stores/auth.ts` 当前仍是演示状态，应接入 `pixflow-app/src/main/java/com/pixflow/app/auth/AuthController.java` 暴露的 `/api/auth/*`。

`pixflow-app/src/main/java/com/pixflow/app/progress/ProgressMessagingConfig.java` 当前注册 `/ws`，而设计和前端使用 `/ws/progress`。任务状态 REST payload 涉及 `pixflow-module-task/src/main/java/com/pixflow/module/task/api/query/TaskStatusView.java`、`TaskQueryServiceImpl.java`、`pixflow-web/src/api/tasks.ts` 和 `pixflow-web/src/runtime/useTask.ts`。

## 设计依据与快速检索关键词

后续实现者应先读这些文档，并用下列关键词快速定位依据。

在 `docs/design-docs/api.md` 中搜索 `范围与约定`，确认所有外部 HTTP API 使用 `/api` 前缀。搜索 `Attachment API`，确认 conversation 附件入口形态。搜索 `V1 采用分片上传协议`、`分片上传协议`、`上传会话存储与生命周期`、`lock:package-upload`、`complete 锁`，定位分片上传端点和锁语义。搜索 `WS /ws/progress`、`/topic/task-progress/{conversationId}/{taskId}`、`/topic/packages/{packageId}/progress`，定位 WebSocket endpoint 和 topic。搜索 `SSE 事件名`、`assistant_delta`、`completed`，定位 SSE payload。搜索 `任务状态 payload`，定位任务状态响应字段。搜索 `Authorization`，定位鉴权要求。

在 `docs/design-docs/module/file.md` 中搜索 `五、分片上传与整包去重`、`Redis（上传会话）`、`UploadSessionService`、`UploadSessionStore`、`ChunkAssembler`、`composeObject`、`lock:package-upload`、`lock:{uploadId}:chunk:{index}`、`ProgressNotifier`，定位后端分片上传实现结构。搜索 `素材包删除语义`，确认素材包已被对话或任务引用后软删优先。

在 `docs/design-docs/module/conversation.md` 中搜索 `REST/SSE 端点形态`、`附件瘦版`、`UPLOAD_IMAGE`、`PACKAGE_REFERENCE`、`附件统一走素材包`、`SSE 流式接出`、`WS /ws/progress`、`确认 REST 边界`，定位对话路由、附件和事件流设计。

在 `docs/design-docs/module/task.md` 中搜索 `ProgressAggregator`、`TaskStatusView`、`下载分发`、`WebSocket 归属`、`process_result`，定位任务状态、进度和产物边界。

在 `docs/design-docs/infra/auth.md` 中搜索 `前端与 WebSocket 接入`、`Authorization: Bearer`、`STOMP CONNECT`、`鉴权过滤链`、`/api/auth/*`，定位登录、JWT、HTTP 和 STOMP 鉴权方式。

在 `docs/design-docs/frontend/README.md` 中搜索 `transport-api.md`、`upload.md`、`chat.md`、`stores-runtime.md`，确认前端文档入口。在 `docs/design-docs/frontend/transport-api.md` 中搜索 `HTTP client`、`SSE`、`STOMP / WebSocket`。在 `docs/design-docs/frontend/chat.md` 中搜索 `/chat/new`、`?q=`、`自动发送`。在 `docs/design-docs/frontend/upload.md` 中搜索 `上传状态机`、`Worker 池`、`续传`、`取消`。在 `docs/design-docs/frontend/stores-runtime.md` 中搜索 `Agent 回合状态机`、`任务运行时`、`上传状态机`。

在 `docs/design-docs/web.md` 中搜索 `HTTP 注入`、`STOMP 客户端`、`十六、分片上传（核心）`、`Agent 回合运行时`、`任务运行时`，定位较早但仍有价值的前端总设计。

## Plan of Work

第一阶段先建立契约保护测试，不急着改页面。后端新增或修正 controller-level 测试，证明 `/api/conversations`、`/api/conversations/{conversationId}/messages`、`/api/files/packages/init`、`/api/files/packages/sessions/{uploadId}`、`/api/files/packages/sessions/{uploadId}/chunks/{index}`、`/api/files/packages/sessions/{uploadId}/complete`、`/ws/progress` 是设计承诺的入口。前端新增 API adapter 测试，断言 `api/conversations.ts`、`api/packages.ts`、`runtime/useAgentTurn.ts`、`runtime/useTask.ts` 使用同一批路径。这个阶段的价值是让错误路径先失败，避免后续修一处漏一处。

第二阶段对齐鉴权。后端已有 `pixflow-app/src/main/java/com/pixflow/app/auth/AuthController.java`，前端应新增或补全 `pixflow-web/src/api/auth.ts`，让 `pixflow-web/src/stores/auth.ts` 调用真实登录、注册、当前用户、登出接口，并保存 access token。`pixflow-web/src/api/client.ts` 在 `request()` 中读取 auth store token，注入 `Authorization: Bearer <token>`。`pixflow-web/src/transport/sse.ts` 同样注入 Authorization，因为 `EventSource` 不能自定义 header，当前使用 fetch + ReadableStream 是正确方向。`pixflow-web/src/transport/ws.ts` 的 STOMP `connectHeaders` 改为 `Authorization: Bearer <token>`，保留 `X-Auth-Token` 只作为短期兼容 fallback 时才使用。401 响应应触发会话过期状态并跳转登录或首页。

第三阶段对齐 conversation 路由和主页新对话流程。后端将 conversation 模块 controller 路由改为 `/api/conversations...`，包括创建会话、列表、详情、删除、历史消息、发送消息 SSE、确认 challenge/submit 和任务取消。可以短期保留无 `/api` 兼容映射，但必须用测试证明 `/api` 是主路径。前端 `ChatPage.vue` 要保证 `cid=new` 时只创建一次会话；`router.replace` 到真实 `/chat/{conversationId}` 后，若 query 中有 `q`，必须等真实 `conversationId` 就绪后调用 `useAgentTurn` 发送；发送成功或开始发送后清理 query，避免刷新重复发送。这里要特别检查 `RouterView` 的 key、`onMounted` 和 route watcher：只依赖 mounted 容易在 query 或 cid 变化时漏发。

第四阶段对齐 SSE 事件 schema。`SseAgentEventSink` 不再对所有事件统一输出 `{ type, text, payload, metadata }`，而是按 `api.md` 输出事件名和 payload。`assistant_delta` 的 data 是 `{ "text": "..." }`；`completed` 的 data 是 `{ "finalText": "...", "traceId": "...", "turnNo": number }`；工具调用、工具结果、确认请求、错误事件也按 `api.md` 字段输出。前端 `useAgentTurn.ts` 的 zod schema 作为验收边界，后端测试用实际 SSE data 片段断言字段存在且没有额外套壳导致前端解析失败。

第五阶段实现后端分片上传协议。`pixflow-module-file` 新增 upload 子包，包含 `UploadSessionService`、`UploadSessionStore`、`ChunkAssembler`、`ChunkIntegrityVerifier`、`FileHashService` 等设计文档中的职责。`POST /api/files/packages/init` 接收文件名、大小、chunkSize、expectedChunks、fileHash 和可选 doc 元数据；如果 fileHash 命中 READY 素材包，返回 `mode: "DEDUP"` 和 `packageId`；否则在 `lock:package-upload:{fileHash}` 下创建 Redis-only 上传会话，返回 `mode: "UPLOAD"`、`uploadId`、`uploadedChunks`。`GET /api/files/packages/sessions/{uploadId}` 返回会话状态和已上传 chunks。`PUT /api/files/packages/sessions/{uploadId}/chunks/{index}` 写临时 MinIO 对象并记录 Redis set，重复上传同 hash 返回 `ALREADY_EXISTS`，hash 不一致返回 `CHUNK_HASH_MISMATCH`。`POST /api/files/packages/sessions/{uploadId}/complete` 在 complete 锁内校验完整性、调用 MinIO `composeObject` 合并、创建 `asset_package`、发送解压 MQ 消息并清理临时分片。`DELETE /api/files/packages/sessions/{uploadId}` 幂等取消并清理 Redis 与临时对象。既有 `POST /api/files/packages` 保留为开发兼容入口，但前端默认不用它。

第六阶段收敛附件机制。前端 `pixflow-web/src/api/attachments.ts` 不能再假设 conversation 有独立的大文件上传职责。优先路径是：用户要把素材带入对话时，先通过 file 模块上传素材包，得到 `packageId`，再在 `POST /api/conversations/{conversationId}/messages` 的 JSON body 中传 `attachments: [{ type: "PACKAGE_REFERENCE", packageId }]`。如果后续保留单图附件入口，`POST /api/conversations/{conversationId}/attachments` 只能做轻量绑定：底层调用 file 模块已有上传/对象存储能力，返回 attachment id 和 source ref，不在 conversation 内部重复实现 MinIO key 规则、hash、清理和 MIME 校验。后端 `AttachmentCollector` 负责把 `PACKAGE_REFERENCE` 展开为图片 attachment 投影。

第七阶段对齐 WebSocket 进度。`ProgressMessagingConfig` 注册 `/ws/progress`，并让 auth WebSocket interceptor 校验 STOMP `Authorization` header。任务进度 topic 使用 `/topic/task-progress/{conversationId}/{taskId}`，素材包上传/解压进度 topic 使用 `/topic/packages/{packageId}/progress`。前端 `useTask.ts` 和 `usePackageProgress.ts` 已使用 `/ws/progress`，只需确认 connect headers、重连和 frame payload 与后端一致。若后端短期保留 `/ws`，也必须把 `/ws/progress` 作为主 endpoint 并覆盖测试。

第八阶段对齐任务状态 REST payload。后端 `TaskStatusView` outward DTO 改为包含 `taskId`、`status`、`taskType`、`progress`、`createdAt`、`startedAt`、`finishedAt`、`lastError` 等字段，其中 `progress` 是对象 `{ done, total, failed }`，可以额外保留 `skipped` 但前端不依赖它。`TaskQueryServiceImpl` 从 `ProcessTask` 和 `ProgressSnapshot` 组装该 DTO。前端 `api/tasks.ts` 和 `runtime/useTask.ts` 按嵌套 `progress` 更新状态，并兼容短期旧字段只作为迁移保护。

第九阶段做端到端验收并清理兼容分支。启动后端和前端，从未登录、登录、主页发 prompt、自动创建会话、SSE 回答、上传 zip、分片续传、任务进度、取消任务、附件引用素材包这一条链路逐项验证。确认所有设计主路径通过后，记录是否仍保留兼容路由；如果保留，应在 `api.md` 或本计划修订记录写清楚移除条件。

## Concrete Steps

从仓库根目录 `D:\study\PixFlow` 开始。先检查工作区，避免覆盖他人改动：

    git status --short

预期可能看到已有未提交改动。执行者只修改本计划相关文件，不得还原无关改动。

第一步运行现有测试，建立基线：

    mvn -pl pixflow-app -am test
    mvn -pl pixflow-conversation -am test
    mvn -pl pixflow-module-file -am test
    mvn -pl pixflow-module-task -am test
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test

如果项目实际前端命令是从 `pixflow-web` 目录运行，则使用：

    cd pixflow-web
    pnpm typecheck
    pnpm test

第二步新增失败优先的契约测试。后端至少覆盖：

- `POST /api/conversations` 返回成功，而不是 404。
- `POST /api/conversations/{conversationId}/messages` 返回 `text/event-stream`，且事件 data 符合 `assistant_delta` / `completed` schema。
- `POST /api/files/packages/init` 能创建上传会话或返回 DEDUP。
- `PUT /api/files/packages/sessions/{uploadId}/chunks/{index}` 能幂等接收分片。
- `POST /api/files/packages/sessions/{uploadId}/complete` 能完成合并并创建素材包。
- STOMP endpoint `/ws/progress` 可握手，并要求 Authorization。
- `GET /api/tasks/{taskId}` 返回嵌套 `progress`。

第三步实现鉴权前端接入。编辑 `pixflow-web/src/api/auth.ts`、`pixflow-web/src/stores/auth.ts`、`pixflow-web/src/api/client.ts`、`pixflow-web/src/transport/sse.ts`、`pixflow-web/src/transport/ws.ts`。验收方式是登录后浏览器网络面板中 HTTP 请求、SSE 请求和 STOMP CONNECT 都有 `Authorization: Bearer ...`。

第四步实现 conversation 路由迁移。编辑 `pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/*.java` 中所有 controller mapping。优先给类加统一 `@RequestMapping("/api")` 或直接修改方法路径，避免遗漏确认和取消端点。同步修正 controller 测试。

第五步修正 `ChatPage.vue` 生命周期。编辑 `pixflow-web/src/pages/ChatPage.vue`，让 `cid` 和 `route.query.q` 变化时都能进入同一套“确保真实会话 -> 自动发送 -> 清理 query”流程。该流程必须有 in-flight guard，避免同一个 `q` 被重复发送。

第六步实现 SSE schema adapter。编辑 `SseAgentEventSink.java`，必要时新增专用 DTO。后端测试直接读取 emitter 输出或通过 MockMvc streaming 断言事件名和 JSON 字段。

第七步实现 file 分片上传。新增 `pixflow-module-file/src/main/java/com/pixflow/module/file/upload/` 包下服务类，并扩展 `FileController.java`。实现时复用现有 `ObjectStorage`、`StorageKeys`、Redis/Redisson 基础设施和 MQ 解压入口，不绕过 file 模块已有素材包状态机。同步补测试。

第八步收敛附件 API。编辑 `pixflow-web/src/api/attachments.ts`、消息发送 DTO、conversation 附件收集相关类。第一版应优先支持 `PACKAGE_REFERENCE`，并让 UI 从已上传素材包中选择引用；如果必须保留单图附件按钮，也要让它走 file 模块上传能力。

第九步实现 `/ws/progress` 和任务 DTO。编辑 `pixflow-app/src/main/java/com/pixflow/app/progress/ProgressMessagingConfig.java`、`pixflow-app/src/main/java/com/pixflow/app/auth/AuthWebSocketInterceptor.java`、`pixflow-module-task/src/main/java/com/pixflow/module/task/api/query/TaskStatusView.java`、`TaskQueryServiceImpl.java`、`pixflow-web/src/api/tasks.ts`、`pixflow-web/src/runtime/useTask.ts`。

第十步跑完整验证，并把结果写回本计划：

    mvn -pl pixflow-app -am test
    mvn -pl pixflow-conversation -am test
    mvn -pl pixflow-module-file -am test
    mvn -pl pixflow-module-task -am test
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test

## Validation and Acceptance

后端契约验收以行为为准。启动后端后，`POST /api/conversations` 不应返回 404；创建会话得到 `conversationId` 后，`POST /api/conversations/{conversationId}/messages` 应返回 SSE 流，事件中至少出现 `assistant_delta` 和 `completed`，并且 `completed` data 中有 `finalText`、`traceId`、`turnNo`。

主页验收：启动前端和后端，登录后打开主页，在输入框输入一段 prompt 并提交。浏览器应跳转到 `/chat/{真实conversationId}`，地址栏不长期停留在 `/chat/new?q=...`；网络面板应看到 `POST /api/conversations` 和 `POST /api/conversations/{conversationId}/messages`，不是 `/conversations`；页面应显示用户消息和助手流式回复。刷新同一真实会话页面，不应重复发送旧 query prompt。

分片上传验收：选择一个 zip 素材包上传。网络面板应按顺序出现 `POST /api/files/packages/init`、多个 `PUT /api/files/packages/sessions/{uploadId}/chunks/{index}`、`POST /api/files/packages/sessions/{uploadId}/complete`。中途断网或刷新后，前端能通过本地 `fileHash -> uploadId` 和 `GET /sessions/{uploadId}` 续传，已上传 chunk 不重复写入。重复上传同一 fileHash 的包时，后端返回 DEDUP 或可接管的上传会话，不重复创建 READY 包。

附件验收：在对话中引用已上传素材包时，请求体包含 `attachments[].type = "PACKAGE_REFERENCE"` 和 `packageId`。后端不创建第二套私有附件对象存储路径，而是通过 file 模块解析素材包图片并投影到对话上下文。若单图附件入口仍存在，它必须复用 file 模块上传/存储服务，并能被同一套引用检查和清理策略覆盖。

WebSocket 验收：前端连接 `ws(s)://host/ws/progress`，STOMP CONNECT header 有 `Authorization: Bearer ...`。任务运行时订阅 `/topic/task-progress/{conversationId}/{taskId}` 后能收到任务进度；素材包上传/解压订阅 `/topic/packages/{packageId}/progress` 后能收到 package 进度。后端 `/ws` 兼容 endpoint 即使保留，也不能是前端默认入口。

任务状态验收：`GET /api/tasks/{taskId}` 返回 JSON 中有 `progress.done`、`progress.total`、`progress.failed`，以及 `createdAt`、`startedAt`、`finishedAt` 中可用的时间字段。前端任务卡片能根据 REST 刷新和 WS frame 更新同一份状态，不出现 NaN、undefined 或进度倒退。

测试验收：上述 Maven 和 pnpm 命令全部通过。新增测试应在修复前能暴露契约失败，修复后通过。

## Idempotence and Recovery

路由迁移可以安全重复执行，但要避免同时存在两个语义不同的 controller 方法处理同一路径。如果保留兼容路由，应让它调用同一 service 方法，不复制业务逻辑。

分片上传必须幂等。重复 `init` 同一 fileHash 时通过 Redisson 锁串行化，返回既有 READY 包或既有上传会话。重复上传同一 `(uploadId, index)` 且 hash 一致时返回成功或 `ALREADY_EXISTS`，不重复写临时对象。重复 `complete` 同一 uploadId 时通过 complete 锁确保只合并一次。重复 `DELETE /sessions/{uploadId}` 时返回成功或 no-op，不抛 500。

附件绑定必须可恢复。消息中只保存 packageId、imageId 或 attachmentRef 等轻量引用；误绑定可以通过删除消息附件关系或重新发送消息修复，不需要清理未知对象存储路径。

鉴权改造要支持退出和 token 失效恢复。401 后清理本地 auth 状态，关闭或重建 SSE/STOMP 连接，不让旧 token 持续重连。

## Artifacts and Notes

当前已观察到的关键不一致如下：

    前端:
      pixflow-web/src/api/conversations.ts -> /api/conversations
      pixflow-web/src/runtime/useAgentTurn.ts -> /api/conversations/{id}/messages
      pixflow-web/src/api/packages.ts -> /api/files/packages/init, /chunks/{index}, /complete
      pixflow-web/src/runtime/useTask.ts -> /ws/progress
      pixflow-web/src/transport/ws.ts -> 当前 X-Auth-Token 为空

    后端:
      pixflow-conversation/.../ConversationController.java -> /conversations
      pixflow-conversation/.../MessageController.java -> /conversations/{id}/messages
      pixflow-module-file/.../FileController.java -> 有 POST /api/files/packages multipart，缺 init/chunks/complete
      pixflow-app/.../ProgressMessagingConfig.java -> 注册 /ws，不是 /ws/progress
      pixflow-module-task/.../TaskStatusView.java -> 扁平 progress 字段，不是 api.md 的嵌套 progress

`files-page-backend-api-plan.md` 已完成 `/files` 页面相关素材图、产物图、删除、重命名、自定义下载接口。本计划不要求回滚或重做这些成果。

## Interfaces and Dependencies

会话主接口：

    POST /api/conversations
    GET /api/conversations
    GET /api/conversations/{conversationId}
    DELETE /api/conversations/{conversationId}
    GET /api/conversations/{conversationId}/messages
    POST /api/conversations/{conversationId}/messages
    POST /api/conversations/{conversationId}/confirm/{proposalId}/challenge
    POST /api/conversations/{conversationId}/confirm/{proposalId}/submit
    POST /api/conversations/{conversationId}/tasks/{taskId}/cancel

SSE 关键事件：

    event: assistant_delta
    data: { "text": "..." }

    event: completed
    data: { "finalText": "...", "traceId": "...", "turnNo": 42 }

分片上传接口：

    POST /api/files/packages/init
    GET /api/files/packages/sessions/{uploadId}
    PUT /api/files/packages/sessions/{uploadId}/chunks/{index}
    POST /api/files/packages/sessions/{uploadId}/complete
    DELETE /api/files/packages/sessions/{uploadId}

分片上传后端依赖：

`pixflow-module-file` 使用 `infra/storage` 的 `ObjectStorage` 和 `StorageKeys` 写临时分片、合并 zip、保存素材包源文件。使用 `infra/cache` 的 Redis/Redisson 保存上传会话、chunk set、init 锁、chunk 锁、complete 锁和 cancel 锁。使用 `infra/mq` 发送解压消息。`package_upload_session` 是 Redis-only 概念，不新增 MySQL 会话表，除非设计文档后续修订。

鉴权接口：

    POST /api/auth/register
    POST /api/auth/login
    GET /api/auth/me
    POST /api/auth/logout

HTTP、SSE、STOMP header：

    Authorization: Bearer <accessToken>

WebSocket endpoint 和 topics：

    WS /ws/progress
    /topic/task-progress/{conversationId}/{taskId}
    /topic/packages/{packageId}/progress

任务状态响应：

    {
      "taskId": "...",
      "taskType": "...",
      "status": "RUNNING",
      "progress": { "done": 320, "total": 800, "failed": 3 },
      "createdAt": "...",
      "startedAt": "...",
      "finishedAt": null,
      "lastError": null
    }

附件消息请求建议形态：

    {
      "text": "请处理这些素材",
      "attachments": [
        { "type": "PACKAGE_REFERENCE", "packageId": 123 }
      ]
    }

如果以后需要单图附件，建议形态是：

    {
      "text": "请看这张图",
      "attachments": [
        { "type": "UPLOAD_IMAGE", "imageId": 456 }
      ]
    }

其中 `imageId` 必须来自 file 模块已有上传/素材图片事实源，不由 conversation 私自创建对象存储文件。

## Revision Notes

2026-07-06 / Codex: 新建计划。原因是用户要求只研究问题和解决方案，不修复代码；同时要求仔细阅读 `docs/design-docs/`，并按 `PLANS.md` 撰写中文计划，说明实现机制、思路和可快速查询设计依据的关键词。本计划明确了分片上传在设计中已经存在但后端未完全落地，也明确附件上传应沿用原文件/素材包上传机制，不做第二套存储。
