# module/conversation —— 对话业务出口（REST/SSE/确认端点 + 附件瘦版 + 会话级锁 + 历史查询 + 进度入站，Wave 4 主循环 + 编排模块）

> 本文是 PixFlow 完整重写阶段 `module/conversation` 模块的设计文档，对应 `design.md` 第三章总体架构（web / Spring Boot 主后端边界）、第六章 6.1「主循环行为」、6.3「HITL 确认」、第九章 9.5「分组聚合」的张数预期 HITL、第十三章数据模型、第十四章异步执行时序的前端-后端边界，以及 `module-dependency-dag-plan.md` 的 **Wave 4 主循环 + 编排模块**。
> 范围：会话生命周期（CRUD）、用户消息入站、SSE 流式接出、附件瘦版收集（用户上传图片 + 素材包引用）、待确认提案 REST 边界、二次确认 challenge、取消/进度推送入站、历史查询只读视图、会话级串行锁。
> 配套阅读：`harness/loop.md`（主循环入口 `stream`/`continueStream` + `AgentEventSink`）、`harness/context.md`（消息模型 + rehydrate + AttachmentContextPreparer）、`harness/session.md`（`message` 表唯一写者、`MessageReadMapper` 只读接口）、`harness/tools.md`（工具集零令牌与确认闸门外移）、`permission/permission.md`（`ConfirmationTokenService` 签发校验）、`module/dag.md`（`PendingPlan` 表 + `process_task` 创建入口）、`module/imagegen.md`（`PendingImagegenPlan` 表 + 生图执行入口）、`module/task.md`（取消标志位 + 进度事件源）、`module/file.md`（`PackageReferenceResolver` 素材包引用）、`infra/storage.md`（MinIO 对象存储）、`infra/cache.md`（Redisson 锁/标志位）、`common.md`（`ProgressNotifier`、`ErrorCode`、`Sanitizer`）。本文不涉及 MVP 既有实现（MVP 无此层），从新架构需求重新推导，按生产级标准设计。

---

## 目录

- [一、文档定位与设计原则](#一文档定位与设计原则)
- [二、与参考实现的本质差异](#二与参考实现的本质差异)
- [三、职责边界与不变量](#三职责边界与不变量)
- [四、模块结构与依赖位置](#四模块结构与依赖位置)
- [五、数据模型与状态机](#五数据模型与状态机)
- [六、REST/SSE 端点形态](#六restsse-端点形态)
- [七、附件瘦版（V1）](#七附件瘦版v1)
- [八、会话级锁与回合并发](#八会话级锁与回合并发)
- [九、SSE 流式接出](#九sse-流式接出)
- [十、确认 REST 边界与二次确认 challenge](#十确认-rest-边界与二次确认-challenge)
- [十一、取消与进度入站](#十一取消与进度入站)
- [十二、历史查询](#十二历史查询)
- [十三、与各模块的接缝契约](#十三与各模块的接缝契约)
- [十四、配置项](#十四配置项)
- [十五、错误处理与降级](#十五错误处理与降级)
- [十六、可观测](#十六可观测)
- [十七、测试策略](#十七测试策略)
- [十八、暂不考虑](#十八暂不考虑)
- [十九、对 design.md 的细化](#十九对-designmd-的细化)
- [Revision Notes](#revision-notes)

---

## 一、文档定位与设计原则

`module/conversation` 处于依赖 DAG 的 **Wave 4 主循环 + 编排模块**，在依赖图上位于 `state → conversation` 与 `hooks → conversation` 末端，是 PixFlow 的 web 边界层。它把"用户的浏览器/前端"与"harness 的协议"之间的全部 HTTP/SSE/WebSocket 收口在 Spring MVC 控制器之后。

模块专属设计原则：

1. **业务出口，不做主循环拥有者**。conversation 模块只负责"把 web 请求转成 `harness.loop.AgentLoop.stream(...)` 调用、把 `AgentEvent` 桥接为 SSE 帧、把 `pending_plan` 提案转成确认 REST 边界"。**主循环的驱动由 `harness/loop` 拥有**，conversation 调它但不被它反向依赖（依赖图中 `loop` 是 conversation 的上游，不是反过来）。

2. **`message` 表唯一写者是 `harness/session`**。conversation **不直接** `INSERT` `message` 行；用户消息、assistant、tool_result、attachment 全部经 `context.MessageStore.appendXxx` → `TranscriptPort` → `session` 写穿透（`design.md §5.3`、`session.md §一`）。conversation 只通过 `session.MessageReadMapper` 的**只读**接口做历史展示查询（写方法仍 session 私有，受 ArchUnit 守护，详见 [十三](#十三与各模块的接缝契约)）。

3. **HITL 闸门落在 conversation 的 REST 边界**。`harness/tools` 明确「工具集零真实副作用、零令牌」，因此 `submit_image_plan` / `submit_imagegen_plan` 调工具只产"提案"——把提案写到 `module/dag.PendingPlan` / `module/imagegen.PendingImagegenPlan` 表（status=PENDING），**不扣下执行扳机**。真实执行必须由用户在 conversation 的 `/confirm/{proposalId}/challenge` + `/confirm/{proposalId}/submit` 两个端点确认后触发：第一次端点按 threshold 决定是否发 challengeToken；第二次端点校验 challenge 答案并签发 `ConfirmationToken`，再交给 `module/dag.DagValidator` 与 `module/task.TaskService` 触发真实执行（`design.md §6.3`、`tools.md §八.3`）。

4. **附件 V1 瘦版，不预留 shared sources/file_state_cache**。参考实现的 `AttachmentCollector` 含 shared sources（`BackgroundTaskNotificationSource`）、`file_state_cache.changed_text_files`、todo_reminder 等多源，PixFlow V1 没有对应需求（无项目级 workspace、无文件状态变更、无 todo、无后台通知源）。V1 只支持「用户上传图片直接进 attachment」与「素材包引用展开为 attachment 列表」两类（详见 [七](#七附件瘦版v1)）。

5. **会话级串行锁是 web 层硬约束**。同会话的相邻回合 / 同时回合必须串行执行（避免 LLM 输出交叉、会话状态撕裂）。这把锁放在 conversation 模块（Redisson `lock:turn:{conversationId}` + TTL + 看门狗），不放在 `harness/loop`（loop 只在回合内线程封闭、不引入跨节点协调）。

6. **V1 不做 SSE `Last-Event-ID` 重连**。SSE 中断即回合异常，前端按"该回合失败"处理、用户重新发消息。重连需要环形缓冲 + 事件幂等 + 顺序保证，复杂度极高且对 V1 核心价值（流式输出）无直接收益。如果未来要做重连能力，作为独立增强项。

7. **Spring MVC 同步 + `SseEmitter`，不引入 WebFlux**。与全栈"阻塞主链路 + 回合内线程封闭"一致；`harness/loop` / `harness/context` / `harness/hooks` / `harness/session` / `harness/eval` 都假设同步模型，WebFlux 会破坏跨模块假设。SSE 在 Spring MVC 中已成熟支持（`SseEmitter` + `MediaType.TEXT_EVENT_STREAM`）。

8. **历史查询 V1 只给全量时间线**。前端按时间线展示所有 message（含压缩纪元 marker 行的可视化标记），不做"压缩后活动链视图"（那是 `message` 表的另一个语义视图，由 `session.load` 提供给 `context.rehydrate`）；如果未来要做"模型当时看到什么"，单独补 `/conversations/{id}/active-chain` 端点，本期 YAGNI。

9. **进度入站走 WebSocket/STOMP，不混 SSE**。`design.md §三` 把 SSE 定位为"LLM 流式"、WebSocket 定位为"任务进度/完成通知"。conversation 维护一条独立 WebSocket 路由（STOMP 订阅频道），与 SSE 流正交。

10. **trace 责任在 loop，conversation 不自记 trace**。`loop` 持有 `TurnTrace` 句柄；conversation 只在 SSE 帧 metadata 中携带 `traceId` / `turnNo`（来自 loop 的 `AgentEvent.metadata`），**不**直接调 `eval.TraceRecorder`，遵循 `harness/eval` 的"trace 责任上移"约定。

11. **错误归一化遵从 common**。所有 controller 抛出的异常经 `common.ErrorNormalizer` 归一化为 `PixFlowException`（category/recovery），由 `@RestControllerAdvice` 翻译为 HTTP/SSE error 帧；模块自治业务码用 `ConversationErrorCode`（enum implements ErrorCode）。

---

## 二、与参考实现的本质差异

参考实现 OneCode 的 web 边界是「CLI 接收 stdin + 异步 console 流」+「无持久 web 状态」，没有 production 级 REST/SSE 端点。其 `services/context/message_store.py` + `services/attachments/collector.py` 提供的是 CLI 内部状态机，并非 web 协议。PixFlow 的 `module/conversation` 是 production-grade Spring MVC 后端，差异点：

| 维度 | OneCode（参考） | PixFlow（本模块） |
|---|---|---|
| 进程模型 | 单进程长驻 CLI | 多节点 Spring Boot，水平扩展 |
| HTTP 形态 | 无（CLI 内） | REST + SSE + WebSocket 三套协议共存 |
| 会话持久化 | JSONL 文件 + session_id | MySQL `conversation` 表 + 每轮 rehydrate（`session.load`） |
| 回合串行 | asyncio 单进程天然串行 | Redisson 分布式锁 `lock:turn:{conversationId}` + TTL + 看门狗 |
| HITL | 编码 Agent 无真实副作用，无 HITL | 闸门外移至带外 REST：挑战 + 令牌（permission 签发/校验） |
| 附件 | workspace 内 `@mention` 路径解析 | V1 瘦版：用户上传图片 + 素材包引用 |
| SSE 重连 | 无 | V1 不做（断线 = 回合异常） |
| 进度推送 | 共享 console | WebSocket/STOMP 独立路由 |

**可借鉴的结构骨架**：附件的"durable 内部 role → 模型调用前投影"理念（`references/attachment-architecture.md`）。**必须重写的内核**：生产级 REST/SSE 控制器、会话级 Redis 分布式锁、确认 REST 边界的 challenge + token 双阶段、与 `harness.session` 的"读 / 写分离"契约。

---

## 三、职责边界与不变量

`design.md §12` 把 `module/conversation` 描述为「对话与消息（SSE 流式、附件关联；对 message 表只读查询，写入经 session）」。本节把这条粗粒度职责拆成八项可实现的子职责，并对每项显式声明"做"与"不做"的边界。

### 3.1 职责清单

| 职责 | 归属 | 边界 |
|---|---|---|
| `conversation` 表 CRUD（开新 / 列 / 详 / 软删） | **conversation** | 仅自身表；不碰 `message` |
| 用户消息入站 POST 端点 | **conversation** | 入口收 prompt + attachments |
| 会话级回合串行锁 | **conversation** | Redisson 分布式锁 |
| SSE 流式接出（loop.AgentEvent → SSE 帧） | **conversation** | 适配层，不持有 loop 状态 |
| 附件瘦版收集（用户上传图片 + 素材包引用） | **conversation** | 不解析 @mention / file diff |
| 待确认提案 REST 边界（challenge + token 双阶段） | **conversation** | 真实副作用的唯一执行闸门 |
| 取消（写 `cancel:task:*`）+ 进度 WebSocket 入站 | **conversation** | 与 `module/task` 对接 |
| 历史查询（只读 `message` 表） | **conversation** | 走 `session.MessageReadMapper` 只读接口 |

### 3.2 边界不变量

以下不变量由 ArchUnit 守护（在 [十七](#十七测试策略) 集中验证）：

1. **`message` 表唯一写者是 `harness/session`**。`com.pixflow.module.conversation..` 不引用 `MessageWriteMapper`（任何 `com.pixflow.harness.session.persistence.MessageWriteMapper` 或具备 `insert` / `replaceForCompaction` 写方法的类型）。
2. **provider-neutral 守护**。`com.pixflow.module.conversation..` 不依赖 `infra/ai` 的具体 provider 适配器（如 `OpenAiModelClient`、`AliyunModelClient`）；只依赖 provider-neutral 接口 `ModelClient` / `ModelStreamEvent`（若直接调 `infra/ai`）。
3. **不组装 prompt 文本、不持有 `ToolDescriptor`**。conversation 永远不构造 `systemPrompt` 字符串、不调用 `ToolRegistry.visibleDescriptors`。prompt 组装与可见工具集是 `agent` 层（Wave 5）的职责。
4. **不评估权限**。conversation 不直接调 `PermissionPolicy.evaluate`（那是 `harness/tools` 执行管线内的步骤）；只调 `ConfirmationTokenService.issueForChallenge` / `verifyChallengeToken` 走确认令牌签发与校验。
5. **不写 `pending_plan` / `pending_imagegen_plan`**。提案写入在 `module/dag.PendingPlanMapper` / `module/imagegen.PendingImagegenPlanMapper`（由 `harness/tools` 的 `submit_image_plan` / `submit_imagegen_plan` handler 在工具执行管线内调用），conversation 只读不写这两张表，统一通过 `PendingProposalRepository` 视图读取。

---

## 四、模块结构与依赖位置

Maven 模块：`pixflow-conversation`（需加入根 `pom.xml` `<modules>` 与 `dependencyManagement`）。源码包：`com.pixflow.module.conversation`

```
module/conversation/
├── api/
│   ├── ConversationController.java        # /conversations CRUD
│   ├── MessageController.java             # POST /conversations/{id}/messages (返回 SSE 流)
│   ├── ConfirmationController.java        # /conversations/{id}/confirm/{proposalId}/{challenge|submit}
│   ├── CancellationController.java        # POST /conversations/{id}/tasks/{taskId}/cancel
│   ├── AttachmentController.java          # POST /conversations/{id}/attachments (multipart)
│   ├── WebSocketController.java           # /ws/progress (STOMP endpoint)
│   └── SseAgentEventSink.java             # loop.AgentEventSink 适配为 SseEmitter
├── app/
│   ├── ConversationService.java           # 会话表 CRUD + 软删除
│   ├── TurnDispatchService.java           # 用户消息入站编排：锁→rehydrate→append→loop.stream
│   ├── HistoryQueryService.java           # 只读 message 表（走 session MessageReadMapper）
│   ├── ConfirmationService.java           # challenge + token 双阶段编排
│   ├── CancellationService.java           # 写 cancel 标志位 + WebSocket 通知
│   └── AttachmentService.java             # attachment 入库与读取
├── attachment/
│   ├── Attachment.java                    # record (id/type/sourceRef/packageId/metadata)
│   ├── AttachmentType.java                # enum: UPLOAD_IMAGE / PACKAGE_REFERENCE
│   ├── AttachmentCollector.java           # V1 瘦版收集
│   └── AttachmentMapper.java              # Attachment <-> context.Message(ATTACHMENT)
├── lock/
│   ├── ConversationLock.java              # Redisson lock:turn:{convId}
│   └── TurnLockHandle.java                # AutoCloseable 句柄，释放锁
├── proposal/
│   ├── PendingProposal.java               # record 统一视图 (proposalId/type/conversationId/...)
│   ├── PendingProposalRepository.java     # 调 dag/imagegen mapper 包装统一读视图
│   └── ProposalThreshold.java             # 大批量阈值比对
├── history/
│   ├── MessageReadAdapter.java            # 调 session.MessageReadMapper → 业务 MessageView
│   ├── MessageView.java                   # 对前端展示的不可变视图
│   └── CompactionMarkerView.java          # 压缩纪元 marker 行展示标记
├── progress/
│   ├── ConversationProgressBridge.java    # common.ProgressNotifier 实现
│   └── WebSocketProgressSink.java         # 经 Spring STOMP 转推到前端订阅
├── persistence/
│   ├── ConversationEntity.java            # conversation 表 PO
│   └── ConversationMapper.java            # 仅 conversation 表的 MyBatis-Plus
├── config/
│   ├── ConversationProperties.java        # 配置类
│   └── ConversationAutoConfiguration.java # 装配 bean
└── error/
    └── ConversationErrorCode.java         # enum implements ErrorCode
```

依赖方向：

```
conversation ──► harness/loop         (AgentLoop.stream / continueStream / AgentEventSink)
conversation ──► harness/context      (MessageStore.appendXxx / ContextEngine rehydrate)
conversation ──► harness/session      (MessageReadMapper 只读接口；MessageView 映射)
conversation ──► harness/permission   (ConfirmationTokenService.issueForChallenge / verifyChallengeToken)
conversation ──► harness/eval         (TurnTrace 不直接持有；通过 loop 的 AgentEvent.metadata 间接读 traceId)
conversation ──► harness/tools        (ToolExecutionContext 不直接持有；仅 import 类型)
conversation ──► harness/hooks        (RuntimeScope / HookEvent 类型参考)
conversation ──► harness/state        (ProgressNotifier 类型 / 取消标志位 Redis 键契约)
conversation ──► infra/ai             (provider-neutral 接口 type；不依赖具体 adapter)
conversation ──► infra/storage        (MinIO 对象存储：附件图片上传)
conversation ──► infra/cache          (Redisson 锁 + cancel 标志位 + MessageChainCache 热缓存)
conversation ──► module/file          (PackageReferenceResolver SPI)
conversation ──► module/dag           (PendingPlanMapper 只读 + DagValidator)
conversation ──► module/imagegen      (PendingImagegenPlanMapper 只读)
conversation ──► module/task          (process_task 创建入口 + task 进度事件源)
conversation ──► common               (PixFlowException / ErrorCode / Sanitizer / ProgressNotifier / Pageable)
conversation ──► contracts            (ConfirmationToken / ConfirmationChallenge / PendingProposal 类型)
```

---

## 五、数据模型与状态机

### 5.1 conversation 表（CRUD 表）

沿用 `design.md §13.1` 的 `conversation` 表定义，本模块拥有该表的写入权：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | varchar PK | 会话 id（前端持有的唯一标识） |
| `title` | varchar | 会话标题（可空，用户首次发消息后由 agent 自动生成或用户改名） |
| `package_id` | varchar, null | 弱绑定的素材包（多对话可复用同一素材包） |
| `archived` | boolean | 软删除标志 |
| `created_at` | datetime | 创建时间 |
| `updated_at` | datetime | 更新时间 |

索引：主键 `id`；普通索引 `(archived, updated_at DESC)` 用于列表查询。

**状态机**：

```
            ┌──────────────┐
            │  ARCHIVED=false (default)  │ ───── DELETE ──► ARCHIVED=true
            └──────────────┘
            │
            ▼ 已创建
       (用户发消息即开始第一回合)
```

### 5.2 pending_proposal 视图（只读）

`module/dag.PendingPlan` 与 `module/imagegen.PendingImagegenPlan` 提案的**统一读视图**，本模块不写这两张表，只读。

| 字段 | 类型 | 来源 |
|---|---|---|
| `proposalId` | varchar | 两表的主键（UUID） |
| `type` | enum | `DAG` 或 `IMAGEGEN`（由 mapper 区分） |
| `conversationId` | varchar | 提案归属会话（提交时由工具 handler 写入） |
| `expectedCount` | int, null | 张数预期（用于 HITL 张数校验） |
| `payload` | json | dag JSON 或生图 prompt + source_image_ids |
| `status` | enum | `PENDING` / `CONFIRMED` / `EXPIRED` / `REJECTED` |
| `createdAt` | datetime | 提案创建时间 |
| `confirmedAt` | datetime, null | 用户确认时间 |

读视图由 `PendingProposalRepository` 提供，封装两个 mapper 的 `findByProposalId` 与 `findByConversationId` 调用。

### 5.3 conversation 的"回合级锁"键约定

由本模块定义（与 `infra/cache.md` 的 Redis 键约定并列）：

| 键 | 用途 | TTL |
|---|---|---|
| `lock:turn:{conversationId}` | Redisson 分布式锁，确保同会话回合串行 | 看门狗续期，单回合 60s（可配置） |
| `challenge:confirmation:{challengeId}` | 二次确认 challenge 状态 | `challenge-ttl`（默认 5min） |
| `cancel:task:{taskId}` | 任务取消标志位（与 `module/task` 共享） | 任务终态后清理 |

`lock:turn:*` 的 TTL 由看门狗每 10s 续期一次（可配置）；`watchdog-interval` 必须显著小于 `lock-ttl`（10s vs 60s 留 6 倍冗余）。崩溃 / 进程被杀时锁由 TTL 自然过期，不留死锁。

### 5.4 confirmation_challenge 表（新增）

由 `permission` 模块拥有（与 `ConfirmationToken` 一起）；本模块只读视图。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | varchar PK | challengeId |
| `proposal_id` | varchar | 关联提案 |
| `conversation_id` | varchar | 所属会话 |
| `prompt` | varchar | 人类可读挑战文本（如"该组实际仅 2 张：确认按 2 张处理，还是漏传图片？"） |
| `expected_answer` | varchar | 预期答案（哈希存储，或语义匹配） |
| `status` | enum | `PENDING` / `CONFIRMED` / `EXPIRED` |
| `created_at` / `expires_at` | datetime | TTL 5min |

challenge 与 `ConfirmationToken` 是两阶段产物：第一次端点签发 challenge，第二次端点校验 challenge 后签发 token。这是 `design.md §6.3` "分组聚合的张数预期不符时同样触发 HITL"的具体落地。

---

## 六、REST/SSE 端点形态

`module/conversation` 暴露五组 REST/SSE 端点。统一约定：

- 请求体 / 响应体均为 JSON（除附件上传走 `multipart/form-data`、SSE 走 `text/event-stream`）。
- 错误响应体为 `{"errorCode": "...", "message": "...", "traceId": "..."}`（来自 `common.ResponseEnvelop`）。
- 所有端点都带 `traceId`（由 web 层 `TraceIdFilter` 注入），用于跨服务追踪与 Rubrics 回放。

### 6.1 会话 CRUD

```
POST   /conversations
       Body:    { title?: string, packageId?: string }
       Response: 200 { conversationId, title, createdAt, packageId? }

GET    /conversations?archived=false&page=0&size=20
       Response: 200 { items: [{ conversationId, title, updatedAt, packageId? }], total }

GET    /conversations/{conversationId}
       Response: 200 { conversationId, title, createdAt, updatedAt, packageId? }

DELETE /conversations/{conversationId}
       Response: 204（软删除，状态置 ARCHIVED=true）
```

### 6.2 用户消息入站 + SSE 流式

```
POST   /conversations/{conversationId}/messages
       Content-Type: application/json | multipart/form-data
       Body (JSON):       { prompt: string, attachments?: [{...}], packageBinding?: { packageId } }
       Body (multipart):  prompt + file uploads（用户直接传图）
       Response: 200 text/event-stream
       SSE 帧序列:
         event: assistant_delta
         data: {"text": "..."}

         event: tool_call_ready
         data: {"toolName": "...", "toolCallId": "...", "toolInput": {...}}

         event: tool_started
         data: {"toolCallId": "..."}

         event: tool_result
         data: {"toolCallId": "...", "content": "...", "externalized": false}

         event: transition
         data: {"reason": "TOOL_USE" | "REACTIVE_COMPACT_RETRY" | ...}

         event: completed
         data: {"finalText": "...", "traceId": "...", "turnNo": 42}

         # 异常路径
         event: error
         data: {"errorCode": "...", "message": "...", "traceId": "..."}
```

> SSE 帧的 `id:` 字段 V1 不填；`Last-Event-ID` 重连 V1 不支持（详见 [一.6](#一文档定位与设计原则)）。每 30s 发一条 `: heartbeat\n\n` 注释帧防代理超时断开（`heartbeat-interval` 可配）。

### 6.3 待确认提案执行

```
POST   /conversations/{conversationId}/confirm/{proposalId}/challenge
       Body:    {} （空体，从 proposalId 取提案内容）
       Response: 200 {
         needChallenge: boolean,
         challenge?: { challengeId, prompt },     # needChallenge=true 时存在
         token?:     { token, expiresAt }          # needChallenge=false 时直接签发
       }

POST   /conversations/{conversationId}/confirm/{proposalId}/submit
       Body:    { challengeAnswer?: string }
       Response: 200 { taskId?, status: 'PENDING' | 'RUNNING' }
       错误:
         400 PROPOSAL_CHALLENGE_FAILED（答案错误）
         410 PROPOSAL_CHALLENGE_EXPIRED（challenge 过期）
         401 CONFIRMATION_TOKEN_INVALID（token 二次使用）
         404 PROPOSAL_NOT_FOUND
         409 PROPOSAL_ALREADY_CONFIRMED
```

### 6.4 取消与 WebSocket 进度

```
POST   /conversations/{conversationId}/tasks/{taskId}/cancel
       Response: 200 { cancelled: true }

WS     /ws/progress（STOMP endpoint）
       订阅频道:
         /topic/task-progress/{conversationId}/{taskId}
       推送帧:
         {"taskId": "...", "done": 320, "total": 800, "status": "RUNNING", "failed": 3}

         {"taskId": "...", "status": "COMPLETED", "completedAt": "..."}
         {"taskId": "...", "status": "CANCELLED", "cancelledAt": "..."}
```

### 6.5 附件上传（用户直传图片）

```
POST   /conversations/{conversationId}/attachments
       Content-Type: multipart/form-data
       Body:    file (binary) + metadata (JSON: { filename, contentType })
       Response: 200 { attachmentId, sourceRef (MinIO key), type: "UPLOAD_IMAGE" }
       流程: 落 MinIO → 写 attachment 元数据到 message 表（role=ATTACHMENT）→ 返回 attachmentId
```

> 附件也可作为 `POST /messages` 的 JSON body 一部分提交（type=PACKAGE_REFERENCE，packageId 由素材包注册时获取），不必每次直传。

### 6.6 历史查询（只读）

```
GET    /conversations/{conversationId}/messages?page=0&size=50&sinceSeq=null
       Response: 200 {
         messages: [
           { id, seq, role, content, toolCallId?, metadata?, createdAt,
             isCompactionBoundary?, isCompactionSummary?, attachedPackageId? }
         ],
         compressionMarkerCount: 3,
         total
       }
       说明: 全量时间线视图（含压缩纪元 marker 行的可视化标记）；不分"压缩后活动链"
```

---

## 七、附件瘦版（V1）

### 7.1 V1 支持的 attachment 类型

| `attachmentType` | 来源 | 投影形态 |
|---|---|---|
| `UPLOAD_IMAGE` | `POST /attachments` multipart 上传 | MinIO 引用 + 缩略图 preview 拼进 synthetic user message |
| `PACKAGE_REFERENCE` | `POST /messages` JSON body（`attachments[].type=PACKAGE_REFERENCE, packageId`） | 经 `PackageReferenceResolver` 展开为多张 `UPLOAD_IMAGE` 形态，逐张走 ATTACHMENT 投影 |

> V1 不支持的 attachment 类型（详见 [十八](#十八暂不考虑)）：`@file` / `@"path"` / `#L10-20` / `directory` / `edited_text_file` / `queued_command` / `background_task_notification` / `relevant_memories` / `nested_memory` / `plan_mode` / `hook_result` / `skill` / `attachment_error`。

### 7.2 AttachmentCollector 瘦版实现

```java
public final class AttachmentCollector {
    public List<Attachment> collect(UserPrompt prompt, PackageBinding binding) {
        var attachments = new ArrayList<Attachment>();

        // 1. 用户上传图片（multipart 上传已在 POST /attachments 落 MinIO + 写 attachment 元数据）
        for (var uploaded : prompt.uploadedImages()) {
            attachments.add(new Attachment(
                uploaded.attachmentId(),
                AttachmentType.UPLOAD_IMAGE,
                uploaded.minioKey(),
                null,                              // packageId
                uploaded.metadata()
            ));
        }

        // 2. 素材包引用展开（调 PackageReferenceResolver）
        if (binding != null && binding.packageId() != null) {
            var pkgRef = packageReferenceResolver.resolve(binding.packageId());
            if (pkgRef.isEmpty()) {
                throw new ConversationException(PACKAGE_REFERENCE_INVALID, ...);
            }
            for (var imgRef : pkgRef.listImages()) {
                attachments.add(new Attachment(
                    imgRef.imageId() + ":" + pkgRef.packageId(),  // 合成 attachmentId
                    AttachmentType.PACKAGE_REFERENCE,
                    imgRef.minioKey(),
                    pkgRef.packageId(),
                    Map.of("viewId", imgRef.viewId(), "groupKey", imgRef.groupKey())
                ));
            }
        }

        return attachments;
    }
}
```

### 7.3 AttachmentMapper（业务层 → context.Message）

```java
public final class AttachmentMapper {
    public List<Message> toContextMessages(List<Attachment> attachments, String conversationId) {
        return attachments.stream().map(att -> new Message(
            MessageIdGenerator.next(),                    // context 内部消息 id
            MessageRole.ATTACHMENT,
            /* content */ "[" + att.type().name() + ":" + att.sourceRef() + "]",  // 最小引用形态
            /* toolCallId */ null,
            MessageMetadata.of(Map.of(
                "attachmentType",   att.type().name(),
                "attachmentRef",    att.sourceRef(),
                "attachedPackageId", att.packageId() == null ? "" : att.packageId(),
                "attachmentMeta",   att.metadata()
            )),
            Instant.now()
        )).toList();
    }
}
```

### 7.4 ATTACHMENT 投影（context 侧，不是 conversation 的职责）

context 的 preparer 链最外层 `AttachmentContextPreparer`（定义在 `harness/context.md §四`/`§五` 扩展，本模块调用即可，不在本模块实现）把上述 `Message(role=ATTACHMENT)` 投影为 synthetic user/assistant/tool_result 消息：

| attachmentType | 投影结果 |
|---|---|
| `UPLOAD_IMAGE` | synthetic user: "用户上传了一张图片，引用：<MinIO key>" + synthetic user 附缩略图 |
| `PACKAGE_REFERENCE` | 对每个展开的 image 走同 `UPLOAD_IMAGE` 投影 |

投影结果**不写回 store**（参考实现 `attachment-architecture.md` 一致约束）。`AttachmentContextPreparer` 由 `context.ContextEngine` 在 `buildForModel` 内部调用，本模块不感知其内部细节。

---

## 八、会话级锁与回合并发

### 8.1 锁的形态

```java
public interface ConversationLock {
    /** 尝试获取会话级锁，TTL 由看门狗续期；返回句柄，close() 时释放。 */
    Optional<TurnLockHandle> tryLock(String conversationId, Duration ttl);
}

public final class TurnLockHandle implements AutoCloseable {
    public void close() { /* Redisson 显式释放 + 取消看门狗续期 */ }
}
```

底层使用 `RedissonClient.getLock("lock:turn:" + conversationId)`，配置 `leaseTime = ttl` + `watchdogTimeout = watchdog-interval`（默认 10s）。同一个会话的连续回合可获得同一把锁（不同线程 / 不同节点），跨节点生效。

### 8.2 锁的获取流程

`TurnDispatchService.stream(...)` 的入口编排：

```java
public void stream(String conversationId, UserPrompt prompt, List<Attachment> attachments, AgentEventSink sink) {
    var lockHandle = lock.tryLock(conversationId, Duration.ofSeconds(properties.lock().ttl()))
        .orElseThrow(() -> new ConversationException(LOCK_ACQUISITION_FAILED, ...));

    try (lockHandle) {
        // 1. 重新跑对话归属校验（软删除 / 跨用户访问）
        var conv = conversationService.requireActive(conversationId);

        // 2. （rehydrate 已由 loop + context 在 stream 入口完成，conversation 无需显式触发）

        // 3. 收集 attachment → 调 context.appendAttachments
        var collected = attachmentCollector.collect(prompt, prompt.packageBinding());
        var contextMessages = attachmentMapper.toContextMessages(collected, conversationId);
        contextMessages.forEach(messageStore::appendAttachment);

        // 4. 追加用户 prompt 触发 replay（trigger rehydrate 让 loop 看到）
        messageStore.appendUser(prompt.text());

        // 5. 驱动 loop.stream（在回合线程内阻塞跑完）
        agentLoop.stream(prompt.text(), collected, sink);

    } finally {
        // AutoCloseable 自动释放锁（含看门狗取消）
    }
}
```

### 8.3 锁的边界与失效场景

| 场景 | 行为 | 备注 |
|---|---|---|
| 同会话两次 POST 顺序 | 第二次请求阻塞等锁，直到第一次回合结束 | Redisson 锁的天然 FIFO |
| 同会话两次 POST 并发（不同节点） | 第二次请求阻塞 | Redisson 跨节点生效 |
| 回合执行崩溃 / Web 端断开 | 看门狗停止，TTL 到期后锁自动释放 | 锁不会死 |
| 回合超时（LLM 拖到 >ttl） | 看门狗续期不中断；turn 自带超时由 loop 配置兜底 | TTL 60s 通常远大于正常 turn |
| 取消（用户点取消） | 锁仍持有，但 loop 在工作单元间检查 cancel 标志并优雅停 | cancel 检查在工作单元间，不抢占锁 |
| 软删除会话的回合请求 | 在 `requireActive` 步返回 410 Gone，不进入锁 | 避免给 ARCHIVED 会话加锁 |

### 8.4 锁与 `harness/loop` 的关系

- loop 自身**不知道**会话级锁的存在；lock 仅在 conversation 的 web 层。
- loop 的回合内并发假设（同一回合内单线程）由 loop 自维护；会话级锁管的是"不同回合之间"不能交叉。
- loop 的 `continueStream`（子 Agent fork）**不**重新获取会话级锁——子 Agent fork 在回合线程内同步完成，与父回合同生命周期。

---

## 九、SSE 流式接出

### 9.1 `SseAgentEventSink` 适配

```java
public final class SseAgentEventSink implements AgentEventSink {
    private final SseEmitter emitter;
    private final ObjectMapper mapper;

    @Override
    public void emit(AgentEvent event) {
        try {
            // heartbeat 之类纯 SSE 帧由 onTimeout / onError 处理
            emitter.send(SseEmitter.event()
                .name(event.type().name().toLowerCase())          // ASSISTANT_DELTA → "assistant_delta"
                .data(mapper.writeValueAsString(event.payload()))
                .build());
        } catch (IOException | IllegalStateException e) {
            // SSE 已断开（前端 reload / 网络断开），停止 loop 内的 emit（loop 内 try-catch 即可）
            throw new SseClientDisconnected(e);   // loop 收到后中止回合（throw 是契约约定）
        }
    }

    public void heartbeat() {
        try { emitter.send(SseEmitter.event().comment("hb").build()); }
        catch (IOException ignored) { /* 静默，loop 继续 */ }
    }
}
```

`SseAgentEventSink` 实现 `harness/loop.AgentEventSink` 接口（SSE 适配 SPI）；loop 持有 sink 实例并同步调用 `emit(...)`；web 层把 `SseEmitter` 与 sink 绑定。

### 9.2 SSE 控制器伪代码

```java
@RestController
public class MessageController {
    @PostMapping(path = "/conversations/{conversationId}/messages",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter postMessage(
            @PathVariable String conversationId,
            @RequestPart String promptJson,
            @RequestPart(required = false) List<MultipartFile> uploads) {
        // 1. 构造 SseEmitter（超时 = properties.sse().timeout()，默认 5min）
        var emitter = new SseEmitter(properties.sse().timeout().toMillis());

        // 2. heartbeat 后台任务
        ScheduledFuture<?> hb = scheduler.scheduleAtFixedRate(
            () -> heartbeat(emitter), 0, heartbeatInterval.toMillis(), MILLISECONDS);

        // 3. 构造 sink
        var sink = new SseAgentEventSink(emitter, mapper);

        // 4. 异步执行（不阻塞 web 线程）—— 调 TurnDispatchService.stream
        //    因为 SseEmitter 阻塞会拖死 Tomcat；实际是 web 线程立即返回 emitter，
        //    由 worker 线程跑完整回合并 emit。
        executor.execute(() -> {
            try {
                turnDispatchService.stream(conversationId, prompt, attachments, sink);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(normalizeException(e));
            } finally {
                hb.cancel(false);
            }
        });

        return emitter;
    }
}
```

### 9.3 异常帧与断开

- 不可恢复错误（如权限拒绝、超阈值二次确认失败）经 `common.ErrorNormalizer` 归一化后，由 `emitter.send` 一个 `event: error` 帧，再 `emitter.completeWithError`。
- 前端 reload / 网络断开 → `emitter.send` 抛 `IOException`，`SseAgentEventSink.emit` 抛 `SseClientDisconnected`；loop 收到后中止本回合并跳到 `COMPLETED`（不再执行后续工具调用，但已落库的消息保留）。
- **关键**：loop 在 emit 抛异常时不应该让主循环崩溃；`harness/loop.md §十` 的"工具 handler 异常"处理逻辑延伸覆盖 SSE 异常：归一化为 `ToolExecutionResult(error=true)` 风格的中止 + abort trace。

### 9.4 与 WebSocket 进度的隔离

SSE 走 `text/event-stream`，WebSocket 走 STOMP 独立 endpoint，二者互不干扰。回合进度（"已处理 320/800"）由 `module/task` 通过 `ConversationProgressBridge` 转推到 STOMP 频道，与 SSE 流的 LLM token 流正交（`design.md §三`）。

---

## 十、确认 REST 边界与二次确认 challenge

### 10.1 双阶段设计

`tools.md §八.3` 要求"工具集零令牌"——Agent 不能携带 `ConfirmationToken`，因此真实执行必须在带外（web 层）。本模块通过两个端点把"是否二次确认"与"执行令牌签发"两个语义分离：

- **第一阶段** `/confirm/{proposalId}/challenge`：根据提案 `expectedCount` 与 `properties.confirmation().batchThreshold()`（默认 50 张）比对——
  - `expectedCount <= threshold` → 直接签发 `ConfirmationToken`，前端收到 token 后立即发第二阶段 `/submit`，跳过 challenge。
  - `expectedCount > threshold` → 签发 `ConfirmationChallenge{challengeId, prompt}`，前端展示给用户，由用户回答（"按实际张数处理" / "漏传图片"）。
- **第二阶段** `/confirm/{proposalId}/submit`：携带 `challengeAnswer`，校验通过后调 `ConfirmationTokenService.issueForChallenge(challenge)` 签发 `ConfirmationToken`，再触发 `module/dag` 或 `module/imagegen` 真实执行（`process_task` 入库 → RocketMQ 派发 / 生图结果写 MinIO + `process_result`）。

> 设计权衡：单端点自决方案（一个端点既签发 token 又决定 challenge）与双端点方案的对比见本计划的 Decision Log。下文专注双端点的实现细节。

### 10.2 ConfirmationService 编排

```java
public final class ConfirmationService {

    public ChallengeOrToken requestChallenge(String conversationId, String proposalId) {
        var proposal = proposalRepository.findByProposalId(proposalId)
            .orElseThrow(() -> new ConversationException(PROPOSAL_NOT_FOUND, ...));

        // 1. 提案状态校验
        if (proposal.status() != PENDING) {
            throw new ConversationException(PROPOSAL_ALREADY_CONFIRMED, ...);
        }
        if (!proposal.conversationId().equals(conversationId)) {
            throw new ConversationException(PERMISSION_DENIED, ...);  // 跨会话访问拦截
        }

        // 2. 阈值判定
        int threshold = properties.confirmation().batchThreshold();   // 默认 50
        if (proposal.expectedCount() == null || proposal.expectedCount() <= threshold) {
            // 低阈值：直接签发 ConfirmationToken（短 TTL，无 challenge）
            var token = confirmationTokenService.issueForExecution(proposal, ...);
            return ChallengeOrToken.token(token);
        }

        // 3. 高阈值：签发 challenge（challenge 不直接执行，纯粹是 QA 步骤）
        var challenge = new ConfirmationChallenge(
            ChallengeIdGenerator.next(),
            proposalId,
            conversationId,
            buildChallengePrompt(proposal),  // 如"该组实际仅 2 张：确认按 2 张处理，还是漏传图片？"
            properties.confirmation().challengeTtl()
        );
        confirmationChallengeRepository.save(challenge);
        return ChallengeOrToken.challenge(challenge);
    }

    public ExecutionResult submitChallenge(
            String conversationId,
            String proposalId,
            String challengeId,
            String challengeAnswer) {

        var challenge = confirmationChallengeRepository.findById(challengeId)
            .orElseThrow(() -> new ConversationException(PROPOSAL_NOT_FOUND, ...));

        if (challenge.status() != PENDING) {
            throw new ConversationException(PROPOSAL_CHALLENGE_EXPIRED, ...);
        }
        if (challenge.expiresAt().isBefore(Instant.now())) {
            throw new ConversationException(PROPOSAL_CHALLENGE_EXPIRED, ...);
        }
        if (!matchesExpectedAnswer(challenge, challengeAnswer)) {
            throw new ConversationException(PROPOSAL_CHALLENGE_FAILED, ...);
        }

        // 1. 校验答案：签发 ConfirmationToken（绑定 proposalId + 操作类型）
        var token = confirmationTokenService.issueForChallenge(challenge);
        challenge.markConfirmed();

        // 2. 触发真实执行
        var proposal = proposalRepository.findByProposalId(proposalId).orElseThrow(...);
        ExecutionResult result = switch (proposal.type()) {
            case DAG      -> dagExecutor.submit(token, proposal);      // module/dag 接收 token + 提案 → process_task 入库
            case IMAGEGEN -> imagegenExecutor.submit(token, proposal); // module/imagegen 同款
        };

        // 3. 更新提案状态
        proposalRepository.updateStatus(proposalId, CONFIRMED, result.taskId());

        return result;
    }
}
```

### 10.3 张数预期不符 HITL（design.md §9.5 落地）

当用户在对话中说"这组三张拼接"，Agent 编译时 `submit_image_plan` 的某个 `compose_group` 节点会带 `expected_count=3`。但实际组内成员数可能不符（上传时漏传）：

- `/challenge` 端点返回 challenge prompt：`"该组实际仅 2 张：确认按 2 张处理，还是漏传图片？"`
- `matchesExpectedAnswer` 接受 `按实际` / `按2张` / `确认` / `2` / `已确认` 等语义匹配；不接受 `3` / `取消` / `重新传`。
- 接受后 challenge 状态置 `CONFIRMED`，签发 token，`process_task` 创建时按"实际 2 张"执行（用户在对话中已通过 challenge 答复"按实际张数处理"）。

### 10.4 token 二次使用防护

`ConfirmationToken` 由 `permission.ConfirmationTokenService` 签发；其 `verify` 必须保证：
- 一次性（验证后立即标记已用，避免 replay）。
- 短 TTL（默认 10min，见 `properties.confirmation().tokenTtl()`）。
- 绑定 `proposalId`（同一 token 不能跨提案使用）。
- 绑定 `conversationId`（同一 token 不能跨会话使用）。

---

## 十一、取消与进度入站

### 11.1 取消流程

```java
public final class CancellationService {
    public void cancelTask(String conversationId, String taskId) {
        // 1. 校验 task 归属（必须属于 conversationId）
        var task = taskService.requireOwnedTask(taskId, conversationId);

        // 2. 写 Redis 取消标志位
        redisTemplate.opsForValue().set(
            "cancel:task:" + taskId,
            Instant.now().toString(),
            Duration.ofMinutes(5)
        );

        // 3. WebSocket 推送取消事件
        webSocketProgressSink.push(
            "/topic/task-progress/" + conversationId + "/" + taskId,
            new ProgressEvent(taskId, Status.CANCELLED, Instant.now())
        );
    }
}
```

`module/task` 的 worker 在工作单元之间检查 `cancel:task:{taskId}`（见 `module/task.md §十`）；命中后优雅停并持久化断点（`process_result` 当前状态全部保留，已在工作单元运行的标记为完成）。

### 11.2 进度入站

```java
@FunctionalInterface
public interface ProgressNotifier {
    void notify(String conversationId, String taskId, ProgressEvent event);
}

@Component
public final class ConversationProgressBridge implements ProgressNotifier {
    private final SimpMessagingTemplate stomp;
    private final Redis 消息监听容器 redisContainer;

    @Override
    public void notify(String conversationId, String taskId, ProgressEvent event) {
        // 直接推（用于同步调用）
        stomp.convertAndSend(
            "/topic/task-progress/" + conversationId + "/" + taskId,
            event
        );
    }

    // Redis pub/sub 订阅入口：module/task 通过 Redis pub/sub 发布进度事件
    @EventListener
    public void onMessage(TaskProgressRedisEvent event) {
        notify(event.conversationId(), event.taskId(), event.toProgressEvent());
    }
}
```

`module/task` 通过两条路径发布进度：(a) `taskService.notify` 直接调用（同步路径）；(b) `ProgressAggregator` 写 Redis pub/sub / RocketMQ topic，异步广播给所有订阅者（包括本模块的 `ConversationProgressBridge`）。本模块对两条路径的消费者使用同一 `SimpMessagingTemplate.convertAndSend` 转推到 STOMP 频道，前端订阅即可。

### 11.3 取消 vs 取消锁的区别

| 取消类型 | 触发 | 影响范围 |
|---|---|---|
| **取消任务**（本模块） | 用户点取消按钮 | 写 `cancel:task:{taskId}`，task worker 优雅停 |
| **取消回合**（隐式） | SSE 断开（前端 reload） | loop 收到 `SseClientDisconnected` 后中止本回合；不取消已起的 task |
| **会话级锁**（本模块） | 自动获取 / 释放 | 同会话回合串行；不影响 task worker |

---

## 十二、历史查询

### 12.1 全量时间线视图（V1）

```java
public final class HistoryQueryService {
    public PagedMessages query(String conversationId, Pageable pageable) {
        var session = sessionRepository.requireActive(conversationId);

        // 走 session.MessageReadMapper 只读接口（写方法被 session 私有保护）
        var msgs = messageReadMapper.findMessagesByConversation(conversationId, pageable);

        return new PagedMessages(
            msgs.stream().map(MessageView::from).toList(),
            msgs.compressionMarkerCount,   // 压缩纪元 marker 计数（仅展示用）
            msgs.total
        );
    }
}
```

### 12.2 压缩纪元 marker 行的可视化

`session.MessageReadMapper.findMessagesByConversation` 返回的 message 包含 `compaction_marker` 字段（来自 `harness/session.md §5.2`）：

- `compaction_marker = null`（普通消息）：前端按时间线展示。
- `compaction_marker = BOUNDARY`（压缩边界）：前端展示为"—— 以下是被压缩的历史 ——"分隔符。
- `compaction_marker = SUMMARY`（压缩摘要）：前端折叠展示，标题为"压缩摘要（trigger=AUTO/MANUAL/REACTIVE）"。

**V1 不做"压缩后活动链视图"**：那是 `session.load` 给 `context.rehydrate` 用的语义（本模块不该读 `session.load`，那是 context 的工作）；如果未来要做"模型当时看到什么"，单独补 `/conversations/{id}/active-chain` 端点。

### 12.3 性能与分页

- 分页：`Pageable(page, size)` + `sinceSeq` 可选游标参数；按 `(conversation_id, seq ASC)` 顺序。
- 大会话优化：当会话 message 数 > 10000 时，按 `(conversation_id, seq DESC)` 取最近 N 条；早期 message 走"按日期范围分页"（前端调用时传 `since` / `until` 日期参数）。
- 缓存：消息列表不在 conversation 层做缓存（热缓存归 `context.MessageChainCache`，由 `infra/cache` 实现，与本模块正交）。

---

## 十三、与各模块的接缝契约

| 对接方 | 契约 |
|---|---|
| `harness/loop` | conversation 调 `AgentLoop.stream(prompt, attachments, sink)` / `continueStream(sink)`；实现 `AgentEventSink`（`SseAgentEventSink`）；构造 `RuntimeScope.main()` 传入。loop 不反向依赖 conversation。 |
| `harness/context` | conversation 调 `MessageStore.appendUser` / `appendAssistant` / `appendToolResults` / `appendAttachments`（由 loop 内部调用，conversation 不直接调）；使用 `context.Message` / `MessageRole.ATTACHMENT` / `MessageMetadata` 类型；`AttachmentContextPreparer` 由 context 内部嵌入 preparer 链，conversation 不感知其细节。 |
| `harness/session` | conversation 只**读** `message` 表：调 `session.MessageReadMapper.findMessagesByConversation` / `findAttachments` 等只读方法。**写方法（`insert` / `replaceForCompaction`）仍 session 私有**，conversation 不引用（ArchUnit 守护）。`session.MessageService.flush()` 由 loop 在回合边界调，conversation 不感知。 |
| `harness/permission` | conversation 调 `ConfirmationTokenService.issueForExecution` / `issueForChallenge` / `verifyChallengeToken`；token 形状由 `contracts.ConfirmationToken` / `ConfirmationClaims` 承载。`ChallengeChallengeRepository` 由 `permission` 拥有，conversation 只通过 SPI 访问（`requireChallenge` / `markConfirmed`）。 |
| `module/file` | conversation 调 `PackageReferenceResolver.resolve(packageId).listImages()` 展开素材包引用；调 `FileService.getAssetImage(attachmentId)` 取图片元数据。不写 `asset_*` 表。 |
| `module/dag` | conversation 调 `DagValidator.validate(dagJson)`（在 `/challenge` 第二阶段触发执行前再校验一次，作为 belt-and-suspenders）；调 `TaskService.createProcessTask(token, dagJson, packageId, ...) -> taskId` 触发确定性路径执行；调 `PendingPlanMapper.findByProposalId` 读提案（只读）。 |
| `module/imagegen` | conversation 调 `PendingImagegenPlanMapper.findByProposalId`（只读）；调 `ImagegenExecutor.submit(token, proposal) -> taskId` 触发生成式路径执行。 |
| `module/task` | conversation 调 `TaskService.requireOwnedTask(taskId, conversationId)` 校验归属；调 `TaskService.cancelTask(taskId)`（与 `module/task.md` 对齐）；订阅 `ProgressAggregator` Redis pub/sub / RocketMQ topic 进度事件。 |
| `harness/state` | conversation 调 `ProgressNotifier.notify` 接口实现进度推送；不直连 `state_store` Redis / MySQL 表（`state` 模块的运行态读模型对 web 层不可见）。 |
| `harness/hooks` | conversation 不直接订阅 hook 事件（loop 在回合内派发；conversation 仅通过 SSE 帧把 hook 派发的影响透出，如 metadata 携带的 `compactTrigger`）；`RuntimeScope.main()` 由 conversation 构造并交给 loop。 |
| `harness/tools` | conversation 不直接调 tools；仅在 `ConfirmationService` 触发执行时由 `module/dag.DagValidator` 间接触发。`tools.md §八.3` "工具集零令牌" 契约由本模块的 `/confirm/.../submit` 出口与 `permission.ConfirmationTokenService` 共同兑现。 |
| `infra/ai` | conversation 不依赖具体 provider 适配器；只 import provider-neutral 接口（如 `ModelStreamEvent`）作为 `AgentEvent` 类型参考；不直接 import `OpenAiModelClient` / `AliyunModelClient`。 |
| `infra/storage` | conversation 调 `ObjectStorage.upload(key, bytes)` 写用户上传图片；调 `ObjectStorage.generatePresignedGetUrl(key)` 给前端预览/下载链接（带 TTL）。 |
| `infra/cache` | conversation 调 `RedissonClient.getLock(...)` 实现会话级锁；调 `RedisTemplate.opsForValue()` 写 `cancel:task:*` 标志位。 |
| `common` | 所有 controller 抛出的异常经 `ErrorNormalizer` 归一化为 `PixFlowException`；文本（错误消息、agent message）经 `Sanitizer` 脱敏；分页响应统一 `PageResponse<T>` 形态。 |

**关键不变量**：

1. `message` 表写者唯一——本模块经 `context → TranscriptPort → session` 写穿透，**不**直连 `MessageWriteMapper`；
2. `module/dag.PendingPlan` / `module/imagegen.PendingImagegenPlan` 写者唯一（由 `harness/tools` 的 handler 写），本模块只读；
3. HITL 闸门落在带外 REST——工具集零令牌、token 闸门在 `permission.ConfirmationTokenService`；
4. 取消与进度走 Redis 标志位 + WebSocket，与 SSE 流正交；
5. trace 责任在 loop——conversation 不自记 trace，仅透传 `traceId`；
6. provider-neutral——本模块不依赖 `infra/ai` 的具体 provider。

---

## 十四、配置项

```yaml
pixflow:
  conversation:
    sse:
      timeout: 5m                  # 单回合 SSE 流超时
      heartbeat-interval: 30s      # heartbeat 帧间隔（防代理超时断开）
    lock:
      ttl: 60s                     # 会话级锁 TTL（看门狗续期基准）
      watchdog-interval: 10s       # 看门狗续期间隔（必须 << ttl）
    confirmation:
      batch-threshold: 50          # 大批量二次确认阈值（张数）
      challenge-ttl: 5m            # challenge 有效期
      token-ttl: 10m               # ConfirmationToken 有效期
      permit-literal-answers: [   # challenge 答案白名单（语义匹配）
        "按实际", "按实际处理", "确认", "已确认"
      ]
    history:
      default-page-size: 50        # 历史查询默认分页
      max-page-size: 200           # 单页最大（防 OOM）
    attachment:
      max-image-size-mb: 20        # 用户单图上传大小上限
      allowed-image-mime:          # 用户上传图片 MIME 白名单
        - image/jpeg
        - image/png
        - image/webp
    progress:
      stomp-endpoint: /ws/progress        # STOMP endpoint
      topic-prefix: /topic/task-progress  # 进度频道前缀
      reconnect-grace: 30s                # WebSocket 断线宽限（用于幂等去重）
```

默认值（生产级）：

- `lock.ttl = 60s` 远大于正常 turn 长度（参考 `loop.md` "Agent 回合请求内同步执行，秒级"）。
- `watchdog-interval = 10s` = `ttl / 6`，给崩溃时锁自动释放留 6 倍冗余。
- `batch-threshold = 50`：与 `design.md §九.5` "大批量二次确认" 范围一致；可由运维按业务调高。
- `token-ttl = 10m`：足够用户在前端完成二次确认（点挑战 → 输入答案 → 提交）。

---

## 十五、错误处理与降级

### 15.1 错误码（`ConversationErrorCode`）

`enum implements ErrorCode`，挂载在 `common.ErrorCode` 体系下：

| code | category | recovery | HTTP | 触发场景 |
|---|---|---|---|---|
| `CONVERSATION_NOT_FOUND` | BUSINESS | NONE | 404 | GET/DELETE 不存在的 conversationId |
| `CONVERSATION_ARCHIVED` | BUSINESS | NONE | 410 | 对已软删会话发消息或确认 |
| `CONVERSATION_TITLE_INVALID` | VALIDATION | SKIP | 400 | 标题超长或包含非法字符 |
| `PROPOSAL_NOT_FOUND` | BUSINESS | NONE | 404 | confirm 端点查不到 proposalId |
| `PROPOSAL_ALREADY_CONFIRMED` | BUSINESS | NONE | 409 | 提案已被确认 |
| `PROPOSAL_CHALLENGE_EXPIRED` | BUSINESS | NONE | 410 | challenge 超过 TTL |
| `PROPOSAL_CHALLENGE_FAILED` | BUSINESS | NONE | 400 | challenge 答案错误 |
| `CONFIRMATION_TOKEN_INVALID` | PERMISSION | NONE | 401 | token 校验失败（二次使用 / 过期 / 伪造） |
| `CONFIRMATION_TOKEN_EXPIRED` | PERMISSION | NONE | 401 | token 超过 TTL |
| `LOCK_ACQUISITION_FAILED` | DEPENDENCY | RETRY | 503 | 锁获取失败（极端并发） |
| `ATTACHMENT_INVALID` | VALIDATION | SKIP | 400 | 附件 MIME / 大小 / 解析失败 |
| `PACKAGE_REFERENCE_INVALID` | BUSINESS | NONE | 404 | 素材包引用解析失败（packageId 不存在 / 解压未完成） |
| `SSE_CLIENT_DISCONNECTED` | INTERNAL | NONE | — | SSE 流断开（loop 收到后中止回合） |

> 通用错误（dependency / validation / internal）走 `common.ErrorCode` 的既有码（`DEPENDENCY` / `VALIDATION` / `INTERNAL`）。

### 15.2 降级矩阵

| 失败场景 | 降级行为 | 影响范围 |
|---|---|---|
| Redis 锁不可用 | 拒绝回合（503 LOCK_ACQUISITION_FAILED），不进入 loop | 用户需重试 |
| Redis 取消标志位写失败 | 返回 503，用户可重试取消 | task 不会立即停，最终会由 `process_task.status` 收尾 |
| WebSocket 推送失败 | 静默重试 3 次（指数退避），仍失败记日志（不阻断 SSE 流） | 进度推送延迟；任务继续执行 |
| 历史查询超时（MySQL 慢） | 返回部分结果 + `nextCursor`，前端继续翻页 | 不阻断对话 |
| 附件上传到 MinIO 失败 | 返回 502，前端重试 | 当次附件丢失，需重传 |
| SSE 心跳失败 | 不阻断回合；SseEmitter 超时由 `sse.timeout` 配置兜底 | 长回合可能因代理超时被截断 |
| DagValidator 在 confirm 端点失败 | 返回 400，前端提示"请重新生成方案" | 用户需重新发消息让 Agent 生成新提案 |

### 15.3 SSE 错误帧与 controller 异常处理

`@RestControllerAdvice` 全局拦截 controller 异常，转 SSE 错误帧或 HTTP 错误响应：

- 普通 REST：`@ExceptionHandler(PixFlowException.class)` → HTTP 状态码 + `{"errorCode", "message", "traceId"}`。
- SSE：在 `SseAgentEventSink.emit(error_event)` 推 `event: error` 帧 + `emitter.completeWithError`。
- 不可恢复异常（DB 断连 / Redis 雪崩 / NPE 逃逸）：经 `ErrorRecorder.record` 落盘 + 归一化为 `INTERNAL` 类 HTTP 500。

---

## 十六、可观测

### 16.1 Micrometer 指标（最小集）

由 `ConversationAutoConfiguration` 注入 `MeterRegistry` 自动暴露，不经 `harness/eval`（`eval` 模块聚焦回合级 trace，会话级运行时指标由本模块自身出）：

- `pixflow.conversation.create{result=ok|error}` / `conversation.list` / `conversation.archive`：CRUD 健康度。
- `pixflow.conversation.turn{result=completed|error|aborted}` + 回合时长计时器：用户回合健康度。
- `pixflow.conversation.lock.wait` 计时器 + `lock.acquired` 计数器：锁获取延迟与频率（高说明会话长）。
- `pixflow.conversation.sse.active`：当前活跃 SSE 流数（gauge）。
- `pixflow.conversation.confirmation{result=challenge|direct_token|token_invalid|challenge_expired}`：确认 REST 调用分布。
- `pixflow.conversation.task.cancel{result=ok|not_found|already_terminal}`：取消分布。
- `pixflow.conversation.attachment.upload{result=ok|too_large|invalid_mime|minio_error}` + size 分布：附件健康度。

### 16.2 trace 责任

本模块**不自记 trace**（不调 `eval.TraceRecorder`）。回合级 trace 由 `harness/loop` 持有 `TurnTrace` 句柄；`AgentEvent` 的 `metadata["traceId"]` 由 loop 注入并由 `SseAgentEventSink` 透传到 SSE 帧；前端用该 `traceId` 即可在 `agent_trace` 表查到完整上下文。

业务级 trace（如"用户在历史查询端的查询"）不进入 `eval.trace`——这是非 Agent 操作；如未来需要，由本模块自建 `conversation_trace` 表（暂不考虑，见 [十八](#十八暂不考虑)）。

### 16.3 结构化日志

- Controller 日志：`SLF4J MDC` 注入 `traceId` / `conversationId` / `turnNo` / `userId`；INFO 级打印"用户消息开始/完成/失败"。
- Application 日志：DEBUG 级打印锁获取/释放、confirm 端点决策、WebSocket 推送结果。
- 错误日志：`ErrorRecorder.record` 自动落盘，含归一化 category / safeMessage / stacktrace（脱敏后）。

---

## 十七、测试策略

### 17.1 单元测试（每个 service / collector / mapper / lock 一组）

- **会话 CRUD**：`ConversationServiceTest` 覆盖开新会话 / 列分页 / 软删除 / 标题校验；用 H2 嵌入式 MySQL + MyBatis-Plus。
- **附件收集**：`AttachmentCollectorTest` 覆盖"用户上传 + 素材包引用 + 空附件 / 素材包不存在 / 素材包为空"四场景。
- **附件映射**：`AttachmentMapperTest` 断言 attachment 列表→ `context.Message(role=ATTACHMENT)` 转换的字段映射正确（`metadata.attachmentType` / `attachmentRef` / `attachedPackageId`）。
- **会话级锁**：`ConversationLockTest` 用嵌入式 Redis（Testcontainers 或 `embedded-redis`）跑：
  - 同会话两次 POST 顺序串行（第二请求阻塞等第一回合结束）；
  - 不同会话并行（不阻塞）；
  - TTL 兜底（持有锁后停掉看门狗续期线程，等 TTL 到期后断言锁自动释放）；
  - 同会话跨节点获取（Testcontainers 多实例 Redis）。
- **SSE 适配**：`SseAgentEventSinkTest` mock `SseEmitter`，断言 `AgentEventType.ASSISTANT_DELTA` 转 `event: assistant_delta` + data JSON 正确；断开时抛 `SseClientDisconnected`。
- **历史查询**：`HistoryQueryServiceTest` 用 `MessageReadMapper` 替身（fake）覆盖"全量 / 分页 / 压缩纪元 marker 计数"。

### 17.2 端到端测试

- **`MessageControllerE2ETest`**：用 Testcontainers（MySQL + Redis + MinIO + RocketMQ + Qdrant），跑完整"开新会话 → POST /messages → 收到 assistant_delta 帧 → 收到 tool_result 帧（如 Agent 调用 search） → 收到 completed 帧 → 流关闭"链路；断言 `session.message` 表新增 message 条数与 SSE 事件一致；归档会话发消息返回 410。
- **`ConfirmationE2ETest`**：构造 `pending_plan` 行（`expectedCount=10` / `=100`）、调 `/challenge` 端点：
  - `10` 场景：返回 `token` 字段，无 challenge；
  - `100` 场景：返回 `challenge` 字段；
  - `/submit` 携带错误答案返回 400；
  - `/submit` 携带正确答案返回 taskId，并断言 `module/task` 创建 `process_task` 入 RocketMQ；
  - 二次提交同 token 返回 401。
- **`CancellationE2ETest`**：构造运行中 task，POST `/cancel` 端点，断言 Redis `cancel:task:{taskId}` 已写、task worker 在下一个工作单元优雅停、WebSocket 推送 `status=CANCELLED` 帧。
- **`ConversationEndToEndIT`**（阶段 7 收尾）：跑"开新会话 → 发消息 → 流式接收 → 提交 DAG 提案 → challenge → submit → task 执行 → 进度推送 → 历史查询"全链路（与 `ConversationEndToEndIT.java` 一致，见 exec-plans 阶段 7）。

### 17.3 ArchUnit 守护（生产级硬约束）

`ConversationArchitectureTest` 集中断言：

1. **唯一写者守护**：`com.pixflow.module.conversation..` 不引用 `MessageWriteMapper`（任何含 `insert` / `replaceForCompaction` 写方法的 mapper）。
2. **provider-neutral 守护**：`com.pixflow.module.conversation..` 不依赖 provider 具体适配器（`OpenAiModelClient` / `AliyunModelClient` 等）；只允许依赖 `ModelClient` / `ModelStreamEvent` 等 provider-neutral 接口。
3. **不组装 prompt 文本**：扫描源文件 `import` 不出现 `systemPrompt` / `ToolDescriptor` 等类型；不持有 `ToolRegistry`。
4. **不评估权限**：不直接调 `PermissionPolicy.evaluate`（仅调 `ConfirmationTokenService`）。
5. **不写 pending 表**：不引用 `PendingPlanMapper` / `PendingImagegenPlanMapper` 的写方法（仅引用只读 `findByProposalId`）。
6. **不引入 Reactor 依赖**：扫描 `pom.xml` 不出现 `spring-boot-starter-webflux`；源代码无 `Mono` / `Flux` 使用。
7. **不自定义 trace**：不调 `eval.TraceRecorder` 的 begin / recordXxx / commit / abort。

### 17.4 属性测试

对会话级锁的属性测试：
- 任意 N 个回合请求序列下，相同 conversationId 的回合执行顺序与请求顺序一致（强串行），不同 conversationId 可并行。
- 单回合崩溃不传播到其他回合。

对附件收集的属性测试：
- 任意 "用户上传图片集合 + 素材包引用集合 + 空 collection" 组合下，`collect` 输出数量 = `用户上传图片数 + 素材包内图片数`。
- attachment 列表的 `id` 唯一性。

### 17.5 集成测试（Testcontainers）

Windows 本地跑 Testcontainers 集成测试需指定 Docker 入口（见 `design.md §4.1`）：

```powershell
$env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'
mvn -pl pixflow-conversation -am verify -Dtest='ConversationEndToEndIT'
```

或直接用 `-Pwindows-docker-tcp` profile。

---

## 十八、暂不考虑

| 项 | 不做的原因 | 未来条件 |
|---|---|---|
| **SSE `Last-Event-ID` 重连** | 复杂度高（环形缓冲 + 事件幂等 + 顺序保证），对 V1 核心价值无直接收益；断线即回合异常、用户重发 | 出现实际重连需求（如长回合 >5min 频繁断线） |
| **`@file` / `@"path"` / `#L10-20` 路径 mention** | PixFlow 无项目级 workspace，文件资产走素材包 | 引入工作区 / 项目概念时 |
| **`file_state_cache.changed_text_files` 主线程 diff** | PixFlow 不监控文件状态变更 | 引入 IDE 集成 / 文件实时监控 |
| **`shared_sources`（后台任务通知源）** | V1 无后台任务通知源（task 进度走 WebSocket） | 引入 background agent 时 |
| **`background_task_notification` attachment** | 同上 | 同上 |
| **`plan_mode` / `hook_result` / `skill` attachment** | PixFlow 无 plan_mode / hook_result / skill 概念（`tools.md §十八` 已定义计划外） | 引入对应机制时 |
| **`relevant_memories` / `nested_memory` attachment** | 记忆召回由 `agent` 层在 prompt 组装前自动注入（`design.md §七`），不走 attachment | 重组记忆召回管线时 |
| **`todo_reminder` attachment** | V1 无 todo 概念 | 引入 todo 时 |
| **压缩后活动链视图（"模型当时看到什么"）** | V1 只给全量时间线；活动链是 `session.load` 给 `context.rehydrate` 的语义 | 前端需要做"模型视角"可视化时 |
| **`conversation_trace` 业务级 trace 表** | `agent_trace` 已涵盖回合级；会话级非 Agent 操作无需 trace | 出现合规审计需求 |
| **会话-节点亲和（sticky session）** | 多节点无亲和，每回合走会话级锁串行 | 出现跨节点锁争用成为瓶颈 |
| **MCP 工具接入** | PixFlow 工具空间固定 7 个，无外部 MCP（`tools.md §十八` 已 lockout） | 长期不引入 |
| **DRM / 视频处理** | `design.md §十六` 暂不考虑视频 | 业务方提出 |
| **语音输入/输出** | V1 不涉及多模态输入除图片 | 业务方提出 |

---

## 十九、对 design.md 的细化

`design.md` 第十二章把 `module/conversation` 描述为「对话与消息（SSE 流式、附件关联；对 message 表只读查询，写入经 session）」。本文据此细化：

| design.md 表述 | 本模块实现细化 |
|---|---|
| 「对话与消息」 | 会话表 CRUD + 用户消息入站 + 历史查询 |
| 「SSE 流式」 | `SseAgentEventSink` 适配 + heartbeat + 异常帧 + worker 线程驱动 |
| 「附件关联」 | V1 瘦版：`UPLOAD_IMAGE` + `PACKAGE_REFERENCE` 两类；ATTACHMENT 投影在 context 侧 `AttachmentContextPreparer` |
| 「对 message 表只读查询」 | `HistoryQueryService` 调 `session.MessageReadMapper` 只读接口，写方法仍 session 私有 |
| 「写入经 session」 | 用户消息经 `context.MessageStore.appendUser` → `TranscriptPort` → `session.TranscriptService.append` 写穿透 |

新增的细化（design.md 未显式给出，本模块设计补充）：

- **会话级锁** `lock:turn:{conversationId}` 是 conversation 模块的责任（design.md §五 隐含但未细化）。
- **确认 REST 边界**（challenge + submit 两个端点）由 conversation 拥有；design.md §6.3 仅描述"确认边界在工具外"，本模块给出具体端点形态。
- **取消 / 进度**入站归 conversation，task 模块通过 Redis pub/sub / RocketMQ topic 解耦。
- **ATTACHMENT 投影**责任在 `harness/context` 的 `AttachmentContextPreparer`，conversation 只负责把 attachment 写到 message 表。

---

## Revision Notes

- 2026-06-30 / Kiro: 新建 `docs/design-docs/module/conversation.md`。确立 conversation 模块为生产级 web 边界层，按 Wave 4 排位落地。固化全部 9 条决策（会话级锁 Redisson 短 TTL + 看门狗 / 确认 REST 双端点 challenge+submit / 附件 V1 瘦版仅 UPLOAD_IMAGE 与 PACKAGE_REFERENCE / ATTACHMENT 投影在 context 侧 / 素材包弱绑定多对话复用 / V1 不做 SSE 重连 / Spring MVC 同步不引入 WebFlux / 历史查询 V1 只给全量时间线 / 附件统一走素材包）。明确 conversation 与 `harness/loop` / `harness/session` 的边界——不写 `message` 表、不写 `pending_*` 表、不组装 prompt、不评估权限，由 ArchUnit 守护。配套落地计划见 `docs/design-docs/exec-plans/conversation-module-implementation-plan.md`。
