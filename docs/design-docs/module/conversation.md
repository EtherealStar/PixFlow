# module/conversation —— 对话业务出口（REST/SSE/确认端点 + 附件瘦版 + 会话级锁 + 历史查询 + 进度入站，Wave 4 主循环 + 编排模块）

> 本文是 PixFlow 完整重写阶段 `module/conversation` 模块的设计文档，对应 `design.md` 第三章总体架构（web / Spring Boot 主后端边界）、第六章 6.1「主循环行为」、6.3「HITL 确认」、第九章 9.5「分组聚合」的张数预期 HITL、第十三章数据模型、第十四章异步执行时序的前端-后端边界，以及 `module-dependency-dag-plan.md` 的 **Wave 4 主循环 + 编排模块**。
> 范围：会话生命周期（CRUD）、用户消息入站、SSE 流式接出、附件瘦版收集（仅 JSON 引用，不直传）、待确认提案 REST 边界、二次确认 challenge（Redis 字符串，不建表）、取消/进度推送入站、历史查询只读视图、会话级串行锁（Redisson 看门狗，不传 leaseTime）。
> 配套阅读：`harness/loop.md`（主循环入口 `stream` + `AgentEventSink`）、`harness/context.md`（消息模型 + rehydrate + AttachmentContextPreparer，**唯一 MessageStore 调用方**）、`harness/session.md`（`message` 表唯一写者、`MessageReadMapper` 只读接口）、`harness/tools.md`（工具集零令牌）、`harness/permission.md`（`ConfirmationTokenService` 签发校验）、`base/contracts.md`（confirmation 令牌形状 + proposal 待确认提案 SPI）、`module/dag.md`（`pending_plan` 表 + `PendingPlanService.confirm` CAS 入口 + `process_task` 创建）、`module/imagegen.md`（生图提案载荷与执行入口）、`module/task.md`（取消与进度事件源）、`module/file.md`（`PackageReferenceResolver` 素材包引用）、`infra/storage.md`（MinIO 对象存储）、`infra/cache.md`（`RedissonClient.getLock` 看门狗配置 + `CacheStore` challenge 存）、`common.md`（`ProgressNotifier`、`ErrorCode`、`Sanitizer`、`PageResponse`）。

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

3. **HITL 闸门落在 conversation 的 REST 边界**。`harness/tools` 明确「工具集零真实副作用、零令牌」，因此 `submit_image_plan` / `submit_imagegen_plan` 调工具只产"提案"——两类提案统一写入 `module/dag` 拥有的 `pending_plan` 表（status=PENDING），**不扣下执行扳机**。真实执行必须由用户在 conversation 的 `/confirm/{proposalId}/challenge` + `/confirm/{proposalId}/submit` 两个端点确认后触发：第一次端点按 threshold 决定是否发 challengeToken；第二次端点校验 challenge 答案并签发 `ConfirmationToken`，再交给 `module/dag.DagValidator` 与 `module/task.TaskService` 触发真实执行（`design.md §6.3`、`tools.md §八.3`）。

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

1. **`message` 表唯一写者是 `harness/session`**。`com.pixflow.module.conversation..` 不引用 `MessageWriteMapper`（任何 `com.pixflow.harness.session.persistence.MessageWriteMapper` 或具备 `insert` / `replaceForCompaction` 写方法的类型）。`MessageStore.appendUser / appendAssistant / appendToolResults / appendAttachments` 由 loop 内部调，**conversation 不直接调**（详见 §13 接缝契约）。
2. **不组装 prompt 文本、不持有 `ToolDescriptor`**。conversation 永远不构造 `systemPrompt` 字符串、不调用 `ToolRegistry.visibleDescriptors`。prompt 组装与可见工具集是 `agent` 层（Wave 5）的职责。
3. **不评估权限**。conversation 不直接调 `PermissionPolicy.evaluate`（那是 `harness/tools` 执行管线内的步骤）；只调 `ConfirmationTokenService.issue / verifyAndConsume` 走确认令牌签发与原子消费。
4. **CAS 责任在 dag 模块**。`pending_plan` 表的状态写者唯一为 `module/dag.PendingPlanService.confirm(...)`（数据库 `UPDATE ... WHERE status='PENDING'` 谓词）；本模块的 `PendingProposalRepository.markConfirmed` **必须**委派给该方法（不允许直接调 `PendingPlanMapper.updateStatus`）。不实现 `contracts.proposal.PendingPlanPort`。
5. **不自定义 confirmation challenge 存储**。challenge 状态走 `infra/cache.CacheStore`，key = `confirm:challenge:{challengeId}`，TTL = `pixflow.conversation.confirmation.challenge-ttl`。**多副本部署下不依赖进程内 `Map`**（历史教训：V1 用 `ConcurrentHashMap` 时只有发起 challenge 的副本能 verify，会随机 50% 失败）。
6. **turn 锁的 leaseTime 永远不传**。见 §5.3 / §8.1，传了直接关掉看门狗，长回合会被并发进入。
7. **SSE 关闭顺序**：`emitter.complete()` 必须在 turn 锁释放之前；否则旧 SSE 残留与新回合并发启动存在窗口。

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
│   ├── AttachmentController.java          # V1 不暴露独立 /attachments；素材上传走 module/file
│   ├── WebSocketController.java           # /ws/progress (STOMP endpoint)
│   ├── SseAgentEventSink.java             # 只做 loop.AgentEvent -> SSE frame
│   ├── SseTurnSession.java                # emitter/heartbeat/worker/cancellation 唯一所有者
│   └── SseTurnSessionFactory.java         # 创建并跟踪活跃 session
├── app/
│   ├── ConversationService.java           # 会话表 CRUD + 软删除
│   ├── TurnPreparationService.java        # 响应提交前 owner/附件/锁/runner 准备
│   ├── PreparedTurn.java                  # 持有 turn lock 与不可变 runner 请求
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
conversation ──► harness/loop         (AgentTurnRunner SPI / AgentEventSink 实现 SseAgentEventSink)
conversation ──► harness/context      (类型参考；**不直接调 MessageStore**，由 loop 内部调)
conversation ──► harness/session      (MessageReadMapper 只读接口；MessageView 映射)
conversation ──► harness/permission   (ConfirmationTokenService.issue / verifyAndConsume)
conversation ──► harness/eval         (TurnTrace 不直接持有；通过 loop 的 AgentEvent.metadata 间接读 traceId)
conversation ──► harness/tools        (ToolExecutionContext 不直接持有；仅 import 类型)
conversation ──► harness/hooks        (RuntimeScope / HookEvent 类型参考)
conversation ──► harness/state        (ProgressNotifier 类型 / 取消标志位 Redis 键契约)
conversation ──► infra/cache          (RedissonClient 看门狗锁 + CacheStore challenge 字符串)
conversation ──► module/file          (PackageReferenceResolver SPI)
conversation ──► module/dag           (PendingPlanMapper 读 / PendingPlanService.confirm CAS / DagValidator)
conversation ──► module/imagegen      (ImagegenConfirmationSupport 只读载荷重算)
conversation ──► module/task          (process_task 创建入口 + task 进度事件源)
conversation ──► common               (PixFlowException / ErrorCode / Sanitizer / ProgressNotifier / PageResponse)
conversation ──► contracts            (ConfirmationToken / ConfirmationChallenge 等 confirmation 类型；不实现 proposal port)
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

`module/dag.PendingPlan` 提案的**统一读视图**，本模块不写 `pending_plan`，只读。DAG 与 imagegen 两类提案共用同一张表，靠 `type` 区分。

| 字段 | 类型 | 来源 |
|---|---|---|
| `proposalId` | varchar | 两表的主键（UUID） |
| `type` | enum | `IMAGE_PLAN` 或 `IMAGEGEN` |
| `conversationId` | varchar | 提案归属会话（提交时由工具 handler 写入） |
| `expectedCount` | int, null | 张数预期（用于 HITL 张数校验） |
| `payload` | json | dag JSON 或生图 prompt + source_image_ids（物理字段沿用 `dag_json`，语义为中立 payloadJson） |
| `status` | enum | `PENDING` / `CONFIRMED` / `EXPIRED` / `REJECTED` |
| `createdAt` | datetime | 提案创建时间 |
| `confirmedAt` | datetime, null | 用户确认时间 |

读视图由 `PendingProposalRepository` 提供，封装 `PendingPlanMapper` / `PendingPlanService` 的读取、状态校验、payloadHash 与 expectedCount 重算。conversation 可以为了确认展示读取提案事实，但不负责入队写入。

### 5.3 conversation 的"回合级锁"键约定

由本模块定义（与 `infra/cache.md` 的 Redis 键约定并列）：

| 键 | 用途 | TTL |
|---|---|---|
| `lock:turn:{conversationId}` | Redisson 分布式锁，确保同会话回合串行 | 由 Redisson `Config.lockWatchdogTimeout` 自动续期（续期间隔 = `lockWatchdogTimeout / 3`），业务层不可配置 |
| `confirm:challenge:{challengeId}` | 二次确认 challenge 状态（Redis 字符串 bucket，存 `ConfirmationChallenge` JSON） | `challenge-ttl`（默认 5min） |
| `cancel:task:{taskId}` | 任务取消标志位（与 `module/task` 共享） | 任务终态后清理 |

`lock:turn:*` 在调用 `RedissonClient.getLock(...).tryLock(waitTime, unit)` 时**不要传 `leaseTime`** —— 显式传 leaseTime 会让 Redisson 直接关掉看门狗，到点强制释放（即便回路还在跑）。看门狗一旦失效（客户端崩溃、网络分区），由 `lockWatchdogTimeout` 自然过期，下一轮回合可获取。锁的释放统一延迟到 SSE `emitter.complete()` 之后（见 §8.2 §9.1），避免旧连接残留 + 新回合并发启动的窗口。

### 5.4 confirmation_challenge（仅 Redis 字符串，不再新增 DB 表）

本模块不再持有 `confirmation_challenge` 表；二次确认 challenge 状态以 Redis `confirm:challenge:{challengeId}` 字符串存 `ConfirmationChallenge` JSON。多副本部署下任意副本的 submit 都能命中。TTL 由 `pixflow.conversation.confirmation.challenge-ttl` 控制，默认 5min。

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

- 请求体 / 响应体均为 JSON（除 SSE 走 `text/event-stream`；V1 不再支持 multipart 直传图片，详见 §6.5）。
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
       Content-Type: application/json
       Body: { prompt: string, attachments?: [...], packageId?: string, metadata?: {...} }
            - attachments: 仅引用型，见 §7（user-attachment-input）。
            - packageId: 本回合要扫的素材包（用不到则不传）。
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
         challenge?:   { challengeId, proposalId, conversationId, prompt,
                         status, createdAt, expiresAt },   # needChallenge=true
         token?:       string                                 # needChallenge=false 时为 null
       }
       说明：
         - 低阈值（needChallenge=false）不签发 token。前端拿到 needChallenge=false 后直接调 submit，
           由 submit 内部统一签发 token + verifyAndConsume + markConfirmed。
         - 高阈值（needChallenge=true）写入 Redis `confirm:challenge:{challengeId}`，
           TTL = pixflow.conversation.confirmation.challenge-ttl。

POST   /conversations/{conversationId}/confirm/{proposalId}/submit
       Body:    { challengeId?: string, challengeAnswer?: string }
                - 低阈值：两个字段都不传。
                - 高阈值：challengeId 必填（从 challenge 响应拿），challengeAnswer 必填。
       Response: 200 { proposalId, taskId?, status: 'CONFIRMED' }
       错误:
         400 PROPOSAL_CHALLENGE_FAILED（答案错误或缺 challengeId）
         410 PROPOSAL_CHALLENGE_EXPIRED（challenge 过期或不存在）
         401 CONFIRMATION_TOKEN_INVALID（token 二次使用）
         404 PROPOSAL_NOT_FOUND
         409 PROPOSAL_ALREADY_CONFIRMED / DAG_PLAN_ALREADY_CONFIRMED
         409 DAG_PLAN_EXPIRED（proposal 已过期，由 cron expireOverdue 标 EXPIRED）
```

### 6.4 取消与 WebSocket 进度

```
POST   /conversations/{conversationId}/tasks/{taskId}/cancel
       Response: 200 { cancelled: true }

WS     /ws/progress（STOMP endpoint）
       订阅频道:
         /topic/task-progress-{conversationId}-{taskId}
       推送帧:
         {"taskId": "...", "done": 320, "total": 800, "status": "RUNNING", "failed": 3}

         {"taskId": "...", "status": "COMPLETED", "completedAt": "..."}
         {"taskId": "...", "status": "CANCELLED", "cancelledAt": "..."}
```

### 6.5 附件：V1 仅引用，不直传

```
V1 不提供独立 `POST /conversations/{conversationId}/attachments` 端点，
也不支持 `POST /messages` 的 multipart 直传二进制。

V1 唯一支持的形态是 JSON 引用：
  POST /conversations/{conversationId}/messages
  Body.attachments: [
    { type: "UPLOAD_IMAGE", attachmentId?, sourceRef, metadata? }
  ]
  Body.packageId: "123"

图片先由前端调 file 模块的素材包上传接口（POST /api/files/packages multipart）
拿到 packageId，再通过 messages 顶层 packageId 绑定。`attachments[]` 只承载已经是具体对象引用的
`UPLOAD_IMAGE`；不再兼容 `attachments[].type=PACKAGE_REFERENCE`。
```

### 6.6 历史查询（只读）

```
GET    /conversations/{conversationId}/messages?page=1&size=50
       Response: 200 PageResponse<MessageView> {
         records: [
           { id, seq, role, content, toolCallId?, metadata?, createdAt,
             isCompactionBoundary?, isCompactionSummary?, attachedPackageId? }
         ],
         total,    # 历史总条数（仅 message 表；不含压缩纪元软删的）
         page,     # 1-based
         size      # 已 clamp 到 [1, maxPageSize]
       }
       说明: 全量时间线视图（含压缩纪元 marker 行的可视化标记）。
       page/size 由 ConversationProperties.History 控制 defaultPageSize / maxPageSize。
```

---

## 七、附件瘦版（V1）

### 7.1 V1 支持的 attachment 类型

| `attachmentType` | 来源 | 投影形态 |
|---|---|---|
| `UPLOAD_IMAGE` | `POST /messages` JSON body（`attachments[].type=UPLOAD_IMAGE, sourceRef`）；sourceRef 由前端先经 `module/file` 上传接口取得 | MinIO 引用 + 缩略图 preview 拼进 synthetic user message |
| `PACKAGE_REFERENCE` | 仅来自 `POST /messages` 顶层 `packageId` 字段；`attachments[].type=PACKAGE_REFERENCE` 不是合法入口 | 经 `PackageReferenceResolver.listImages(packageId)` 展开为多张 `UPLOAD_IMAGE` 形态，逐张走 ATTACHMENT 投影 |

> V1 不支持的 attachment 类型（详见 [十八](#十八暂不考虑)）：`@file` / `@"path"` / `#L10-20` / `directory` / `edited_text_file` / `queued_command` / `background_task_notification` / `relevant_memories` / `nested_memory` / `plan_mode` / `hook_result` / `skill` / `attachment_error`。

### 7.2 AttachmentCollector 瘦版实现

```java
public final class AttachmentCollector {
    private final PackageReferenceResolver packageReferenceResolver;

    public List<Attachment> collect(UserPrompt prompt, PackageBinding binding) {
        List<Attachment> result = new ArrayList<>();
        // 1. 用户上传图片（sourceRef / attachmentId 已在 file 模块落库，前端以引用形式传入）
        if (prompt != null) {
            for (UserAttachmentInput input : prompt.attachments()) {
                if (input.type() != AttachmentType.UPLOAD_IMAGE) {
                    throw new BusinessException(ATTACHMENT_INVALID, ...);
                }
                result.add(new Attachment(
                    blankToGenerated(input.attachmentId()),
                    AttachmentType.UPLOAD_IMAGE,
                    input.sourceRef(),
                    input.packageId(),
                    input.metadata()
                ));
            }
        }
        // 2. 素材包引用展开
        if (binding != null && binding.present()) {
            List<ImageReference> images = packageReferenceResolver.listImages(binding.packageId());
            for (ImageReference img : images) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("imageId", img.imageId());
                meta.put("originalPath", img.originalPath());
                meta.put("skuId", img.skuId());
                meta.put("groupKey", img.groupKey());
                meta.put("viewId", img.viewId());
                result.add(new Attachment(
                    "pkg-" + binding.packageId() + "-" + img.imageId(),
                    AttachmentType.PACKAGE_REFERENCE,
                    img.objectKey(),
                    binding.packageId(),
                    meta
                ));
            }
        }
        return List.copyOf(result);
    }
}
```
注意：
- `PackageBinding` 是普通 record，不是 Optional：`binding.present()` 等价于 `binding.packageId() != null && !packageId.isBlank()`。
- 输入侧 rejection（imageTypeMime / size）由 file 模块在上传时完成；AttachmentCollector 只校验 type 是否为 UPLOAD_IMAGE。

### 7.3 AttachmentMapper（业务层 → loop.Attachment / context.Message）

```java
public final class AttachmentMapper {

    /** 业务侧 Attachment → loop 侧 Attachment（传给 AgentTurnRunner） */
    public List<com.pixflow.harness.loop.Attachment> toLoopAttachments(List<Attachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return List.of();
        return attachments.stream()
            .map(att -> new com.pixflow.harness.loop.Attachment(
                att.attachmentId(),
                "image",                    // loop 侧类型字段固定为 image
                att.sourceRef(),
                att.metadata()))
            .toList();
    }

    /** 业务侧 Attachment → context.Message(role=ATTACHMENT) */
    public Message toContextMessage(Attachment attachment) {
        MessageMetadata md = MessageMetadata.empty()
            .with(MessageMetadata.ATTACHMENT_ID,   attachment.attachmentId())
            .with(MessageMetadata.ATTACHMENT_TYPE, attachment.type().name())
            .with(MessageMetadata.ATTACHMENT_REF,  attachment.sourceRef())
            .with(MessageMetadata.ATTACHED_PACKAGE_ID, attachment.packageId())
            .with("attachmentMetadata", attachment.metadata());
        return Message.attachment(attachment.sourceRef()).withMetadata(md);
    }
}
```
注意：本模块**不直接调 MessageStore**，ATTACHMENT 投影由 loop 在 stream 内部按 context 的 preparer 链完成（见 §13 接缝契约）。

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
public final class ConversationLock {
    /** 尝试获取会话级锁；不传 leaseTime，由 Redisson 看门狗按 lockWatchdogTimeout 自动续期。
     *  返回句柄，close() 时显式释放。 */
    public Optional<TurnLockHandle> tryLock(String conversationId);
}

public final class TurnLockHandle implements AutoCloseable {
    public void close() { /* Redisson 显式释放锁（持有线程校验） */ }
}
```

底层使用 `RedissonClient.getLock("lock:turn:" + conversationId)`，调用 `tryLock(waitTime, unit)` 时**不传 leaseTime**：
- Redisson 会按 `Config.lockWatchdogTimeout` 自动续期（默认 30s，续期间隔 = `lockWatchdogTimeout / 3`，业务层不可配置）。
- 看门狗一旦失效（客户端崩溃 / 网络分区）由 `lockWatchdogTimeout` 自然过期，下一轮回合可获取。
- **禁止**传 `leaseTime`，否则 Redisson 会关闭看门狗，到点强制释放——长回合会被并发进入。

锁的 waitTime 由 `pixflow.conversation.lock.wait-time` 控制，默认 2s。

### 8.2 锁的获取流程

消息入口采用 prepare/commit 两阶段。`TurnPreparationService.prepare(...)` 在 controller 返回 `SseEmitter` 前完成 owner 校验、请求归一化、附件读取、会话锁获取和 runner 解析：

```java
public PreparedTurn prepare(long ownerUserId,
                            String conversationId,
                            MessageSubmitRequest request) {
    conversationService.requireActive(ownerUserId, conversationId);
    var prompt = new UserPrompt(request == null ? "" : request.prompt(),
                                request == null ? List.of() : request.attachments());
    TurnLockHandle handle = conversationLock.tryLock(conversationId)
        .orElseThrow(() -> new PixFlowException(LOCK_ACQUISITION_FAILED, ...));
    try {
        var collected = attachmentCollector.collect(prompt, new PackageBinding(request.packageId()));
        AgentTurnRunner runner = agentTurnRunnerRegistry.resolve();
        return new PreparedTurn(ownerUserId, conversationId, prompt.text(),
            attachmentMapper.toLoopAttachments(collected), runner, handle);
    } catch (RuntimeException error) {
        handle.close();
        throw error;
    }
}
```

`PreparedTurn` 拥有 `TurnLockHandle`，并用 `AgentTurnRequest` 把 prompt、附件和真实 `CancellationToken` 交给 runner。获取锁后的任何准备失败都立即 close；准备成功后锁所有权只属于 `PreparedTurn`。

`TurnLockHandle` 记录获取时的 Redisson owner thread id。因为 prepare 在请求线程取锁、worker 退出后释放，close 使用 `unlockAsync(ownerThreadId)` 并等待完成；重复 close 幂等。终止顺序固定为：停止 heartbeat → 可选 error frame → `emitter.complete()`/确认 transport 已关闭 → worker 实际退出 → `PreparedTurn.close()`。这样旧 SSE 与新回合不会重叠。

### 8.3 锁的边界与失效场景

| 场景 | 行为 | 备注 |
|---|---|---|
| 同会话两次 POST 顺序 | 第二次请求等待 `lock.wait-time`；旧 worker 退出并释放锁后才可进入 | 不提前释放 |
| 同会话两次 POST 并发（不同节点） | 第二次请求阻塞 | Redisson 跨节点生效 |
| 回合执行崩溃 / Web 端断开 | session 发协作取消；worker 真正退出后显式释放；进程崩溃才依赖看门狗超时 | 锁不会因 callback 提前释放 |
| 回合超时（LLM 拖到 >ttl） | 看门狗续期不中断；turn 自带超时由 loop 配置兜底 | TTL 60s 通常远大于正常 turn |
| 取消（用户点停止/断连/超时） | 同一 token 传播到 loop/model/tools；future best-effort interrupt；worker 退出前锁仍持有 | 不使用 `Thread.stop` |
| 软删除会话的回合请求 | 在 `requireActive` 步返回 410 Gone，不进入锁 | 避免给 ARCHIVED 会话加锁 |

### 8.4 锁与 `harness/loop` 的关系

- loop 自身**不知道**会话级锁的存在；lock 仅在 conversation 的 web 层。
- loop 的回合内并发假设（同一回合内单线程）由 loop 自维护；会话级锁管的是"不同回合之间"不能交叉。
- loop 的 `continueStream`（子 Agent fork）**不**重新获取会话级锁——子 Agent fork 在回合线程内同步完成，与父回合同生命周期。

---

## 九、SSE 流式接出

### 9.1 `SseAgentEventSink` 适配

`SseAgentEventSink` 只负责 `AgentEvent -> SSE frame` 投影和发送，不拥有 emitter complete。所有 send 共用同一 send lock；`IOException` 与 emitter 已结束产生的 `IllegalStateException` 通知 session transport failure，并抛 `OperationCancelledException(CLIENT_DISCONNECTED)`。终态业务错误使用 `sendError(PixFlowException)`，只写一帧，不关闭 emitter。late write 由 session metrics 计数。

### 9.2 SSE 控制器伪代码

`MessageController` 只做四步：`preparationService.prepare(ownerUserId, conversationId, request)`、`sessionFactory.create(prepared)`、`session.start()`、返回 `session.emitter()`。conversation 不把 `SecurityContext` 传播到 worker；ownerUserId 在初始 REQUEST 同步捕获。

`SseTurnSession` 是 emitter、sink、heartbeat、worker `FutureTask`、cancellation source 和终态 CAS gate 的唯一所有者，状态为 `NEW/RUNNING/CANCELLING/TERMINATED`。executor 使用 `core=0/max=max-concurrency/SynchronousQueue/AbortPolicy`，无等待队列；拒绝发生在 controller 返回 emitter 前，关闭 prepared lock 并抛 HTTP 503 `TURN_CAPACITY_EXCEEDED`。

运行中取消时 `FutureTask.cancel(true)` 可能在 callable 真正退出前触发 `done()`。实现用 started/exited gate 延迟 ownership close：启动前取消由 `done()` 直接释放，已启动任务必须等 callable finally 标记 exited 后才关闭 transport/lock。

### 9.3 异常帧与断开

- 已提交 SSE 的业务失败：ErrorNormalizer → 最多一个 `event:error` → 普通 `emitter.complete()`；预期路径禁止触发 Servlet 异常完成分派。
- reload/网络断开/timeout：不再尝试写 error 帧；session 关闭写通道、取消模型订阅和工具 future。loop 将 trace 标为 `CANCELLED`，不写 ErrorRecorder、不 emit completed。
- `onCompletion` 可能由本方 `complete()` 重入，因此只有 `RUNNING` 状态的外部 completion 才能触发客户端取消；正常成功/错误赢家已进入 `TERMINATED`，不会被反向取消。

### 9.4 与 WebSocket 进度的隔离

SSE 走 `text/event-stream`，WebSocket 走 STOMP 独立 endpoint，二者互不干扰。回合进度（"已处理 320/800"）由 `module/task` 通过 `ConversationProgressBridge` 转推到 STOMP 频道，与 SSE 流的 LLM token 流正交（`design.md §三`）。

---

## 十、确认 REST 边界与二次确认 challenge

### 10.1 双阶段设计

`tools.md §八.3` 要求"工具集零令牌"——Agent 不能携带 `ConfirmationToken`，因此真实执行必须在带外（web 层）。本模块通过两个端点把"是否二次确认"与"执行令牌签发"两个语义分离：

- **第一阶段** `/confirm/{proposalId}/challenge`：根据提案 `expectedCount` 与 `properties.confirmation().batchThreshold()`（默认 50 张）比对——
  - `expectedCount <= threshold` → 直接签发 `ConfirmationToken`，前端收到 token 后立即发第二阶段 `/submit`，跳过 challenge。
  - `expectedCount > threshold` → 签发 `ConfirmationChallenge{challengeId, prompt}`，前端展示给用户，由用户回答（"按实际张数处理" / "漏传图片"）。
- **第二阶段** `/confirm/{proposalId}/submit`：携带 `challengeId + challengeAnswer`（高阈值）或空体（低阈值），校验通过后由本服务签发 `ConfirmationToken` 并 `verifyAndConsume`，再触发 `module/dag` 或 `module/imagegen` 真实执行（`process_task` 入库 → RocketMQ 派发 / 生图结果写 MinIO + `process_result`）。`pending_plan` 状态迁移委托给 `PendingPlanService.confirm(...)`（CAS）。

> 设计权衡：单端点自决方案（一个端点既签发 token 又决定 challenge）与双端点方案的对比见本计划的 Decision Log。下文专注双端点的实现细节。

### 10.2 ConfirmationService 编排

```java
public final class ConfirmationService {

    private static final String CHALLENGE_NS = "conversation.confirm.challenge";

    private final ConversationService conversationService;
    private final PendingProposalRepository proposalRepository;
    private final ProposalThreshold proposalThreshold;
    private final ConfirmationTokenService tokenService;
    private final TaskCommandService taskCommandService;
    private final ConversationProperties properties;
    private final CacheStore cacheStore;          // infra/cache.RedissonCacheStore，多副本共享
    private final Clock clock;

    /** 阶段一：发起确认。低阈值不签 token，由 submit 统一签发；高阈值写 Redis challenge。 */
    public ConfirmationChallengeResponse challenge(String conversationId, String proposalId) {
        conversationService.requireActive(conversationId);
        PendingProposal proposal = requirePendingForConversation(conversationId, proposalId);

        if (!proposalThreshold.requiresChallenge(proposal)) {
            // 低阈值：needChallenge=false；前端直接调 submit，由 submit 签发 token。
            return new ConfirmationChallengeResponse(false, null, null);
        }

        // 高阈值：写 challenge 到 Redis confirm:challenge:{challengeId}
        Duration ttl = properties.getConfirmation().getChallengeTtl();
        ConfirmationChallenge challenge = new ConfirmationChallenge(
            UUID.randomUUID().toString(),
            proposal.proposalId(),
            conversationId,
            "该提案将处理 " + proposal.expectedCount() + " 张图片，请输入"确认"继续。",
            ConfirmationChallengeStatus.PENDING,
            clock.instant(),
            clock.instant().plus(ttl));
        cacheStore.put(challengeKey(challenge.challengeId()), challenge, ttl);
        return new ConfirmationChallengeResponse(true, challenge, null);
    }

    /** 阶段二：提交确认。CAS 由 PendingPlanService.confirm 保证；DAG 行 status 谓词防并发。 */
    public ConfirmationSubmitResponse submit(String conversationId,
                                              String proposalId,
                                              ConfirmationSubmitRequest request) {
        conversationService.requireActive(conversationId);
        PendingProposal proposal = requireForSubmit(conversationId, proposalId);
        if (proposal.status() == PendingProposalStatus.CONFIRMED) {
            // 幂等：已被并发 confirm，回既有 taskId。
            return new ConfirmationSubmitResponse(proposal.proposalId(), proposal.taskId(), "CONFIRMED");
        }

        boolean high = proposalThreshold.requiresChallenge(proposal);
        if (high) {
            verifyChallenge(request);   // 答案校验 + 一次性消费 Redis key
        }

        ConfirmationToken token = issueToken(proposal);
        verifyAndConsume(token, proposal);   // permission 原子消费
        String taskId = createTaskIfPossible(proposal);

        PendingProposal confirmed;
        try {
            confirmed = proposalRepository.markConfirmed(proposal, taskId);
        } catch (BusinessException ex) {
            String ec = ex.code().code();
            if ("DAG_PLAN_ALREADY_CONFIRMED".equals(ec) || "DAG_PLAN_EXPIRED".equals(ec)) {
                // 并发竞争：重读拿最新 taskId 给前端幂等响应。
                PendingProposal latest = proposalRepository.require(proposalId);
                return new ConfirmationSubmitResponse(latest.proposalId(), latest.taskId(), "CONFIRMED");
            }
            throw ex;
        }
        return new ConfirmationSubmitResponse(confirmed.proposalId(), confirmed.taskId(), "CONFIRMED");
    }

    private void verifyChallenge(ConfirmationSubmitRequest request) {
        if (request == null || request.challengeId() == null || request.challengeId().isBlank()) {
            throw new BusinessException(PROPOSAL_CHALLENGE_FAILED, "challenge id is required");
        }
        Optional<ConfirmationChallenge> stored =
            cacheStore.get(challengeKey(request.challengeId()), ConfirmationChallenge.class);
        if (stored.isEmpty() || !stored.get().expiresAt().isAfter(clock.instant())) {
            throw new BusinessException(PROPOSAL_CHALLENGE_EXPIRED, "challenge expired");
        }
        String answer = request.challengeAnswer() == null ? "" : request.challengeAnswer().trim();
        if (!properties.getConfirmation().getPermitLiteralAnswers().contains(answer)) {
            throw new BusinessException(PROPOSAL_CHALLENGE_FAILED, "challenge answer rejected");
        }
        cacheStore.delete(challengeKey(request.challengeId()));   // 一次性消费
    }

    private CacheKey challengeKey(String challengeId) {
        return new CacheKey("confirm:challenge:" + challengeId,
                            properties.getConfirmation().getChallengeTtl(),
                            CHALLENGE_NS);
    }
}
```

关键接缝约定：
- **CAS 责任在 dag 模块**：`PendingProposalRepository.markConfirmed` 委托 `PendingPlanService.confirm(...)`，
  由数据库 `UPDATE pending_plan SET status='CONFIRMED' WHERE id=? AND status='PENDING'` 保证一次。
- **多副本部署**：challenge 走 Redis，不依赖进程内 map；token 走 `ConfirmationTokenService`。
- **不耦合 turn 锁**：confirmation 是提案落库后的副作用，与当前回合是否还在跑解耦；并发回合 vs 并发 confirmation 的最终一致性由 dag 行 CAS 兜底。

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
            "/topic/task-progress-" + conversationId + "-" + taskId,
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
public final class ConversationProgressBridge {
    private final ProgressNotifier progressNotifier;
    private final ConversationProperties properties;

    public void onProgress(String conversationId, String taskId, Object event) {
        // 频道 = `pixflow.conversation.progress.topic-prefix` + conversationId + taskId
        progressNotifier.publish(channel(conversationId, taskId), event);
    }

    private String channel(String conversationId, String taskId) {
        return properties.getProgress().getTopicPrefix() + "-" + conversationId + "-" + taskId;
    }
}
```
注意：
- `ProgressNotifier` 是 `common.progress.ProgressNotifier` SPI，由 app 层的 `StompProgressNotifier`（`@Primary`）实现，包装 `SimpMessagingTemplate.convertAndSend("/topic/...")`。
- 不在本模块直接持有 `SimpMessagingTemplate` 或 Redis 订阅容器；进度入站与广播通过 SPI 倒置注入，与 STOMP 实现解耦。
- 同一 taskId 进度对前端推送的频道在 confirmation 与回合两类入口下完全一致（同一 `topicPrefix` 派生规则）；前端拿到 taskId 后按 `subscribe(taskProgress(taskId))` 即可。

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
    private final ConversationService conversationService;
    private final MessageReadMapper messageReadMapper;
    private final ConversationProperties properties;

    public PageResponse<MessageView> timeline(String conversationId, Long page, Long size) {
        conversationService.requireActive(conversationId);
        long resolvedPage = page == null ? 1L : Math.max(1L, page);
        long resolvedSize = size == null
            ? properties.getHistory().getDefaultPageSize()
            : Math.max(1L, Math.min(size, properties.getHistory().getMaxPageSize()));
        long offset = (resolvedPage - 1L) * resolvedSize;
        long total = messageReadMapper.countMessagesByConversation(conversationId);
        List<MessageView> records = messageReadMapper
            .findMessagesByConversation(conversationId, offset, resolvedSize)
            .stream()
            .map(MessageView::from)
            .toList();
        return PageResponse.of(records, total, resolvedPage, resolvedSize);
    }
}
```
分页边界：`page` 1-based，`size` clamp 到 `[1, maxPageSize]`（默认 50 / 200）。`PageResponse` 字段 = `records + total + page + size`，**不包含** `compressionMarkerCount`（压缩纪元 marker 在前端通过 `MessageView.isCompactionBoundary / isCompactionSummary` 识别）。

### 12.2 压缩纪元 marker 行的可视化

`session.MessageReadMapper.findMessagesByConversation` 返回的 message 包含 `compaction_marker` 字段（来自 `harness/session.md §5.2`）：

- `compaction_marker = null`（普通消息）：前端按时间线展示。
- `compaction_marker = BOUNDARY`（压缩边界）：前端展示为"—— 以下是被压缩的历史 ——"分隔符。
- `compaction_marker = SUMMARY`（压缩摘要）：前端折叠展示，标题为"压缩摘要（trigger=AUTO/MANUAL/REACTIVE）"。

**V1 不做"压缩后活动链视图"**：那是 `session.load` 给 `context.rehydrate` 用的语义（本模块不该读 `session.load`，那是 context 的工作）；如果未来要做"模型当时看到什么"，单独补 `/conversations/{id}/active-chain` 端点。

### 12.3 性能与分页

- 分页：`PageResponse` 1-based `page + size`，`size` clamp 到 `[1, maxPageSize]`，按 `(conversation_id, seq ASC)` 顺序。
- 大会话优化：当会话 message 数 > 10000 时，按 `(conversation_id, seq DESC)` 取最近 N 条；早期 message 走"按日期范围分页"（前端调用时传 `since` / `until` 日期参数）。
- 缓存：消息列表不在 conversation 层做缓存（热缓存归 `context.MessageChainCache`，由 `infra/cache` 实现，与本模块正交）。

---

## 十三、与各模块的接缝契约

| 对接方 | 契约 |
|---|---|
| `harness/loop` | conversation 构造包含 prompt、attachments 与 `CancellationToken` 的 `AgentTurnRequest`，再调用 `AgentTurnRunner.stream(request, sink)`（SPI，由 agent 模块实现）；实现 `AgentEventSink`（`SseAgentEventSink`）。loop 不反向依赖 conversation；conversation **不直接调 AgentLoop/AgentOrchestrator 内部方法**。 |
| `harness/context` | `MessageStore.appendUser / appendAssistant / appendToolResults / appendAttachments` **由 loop 内部调用**，conversation **不直接调**（ArchUnit 守护 `MessageWriteMapper` 不可达）。conversation 通过 `AgentTurnRequest` 传递 prompt 与 attachments，由 loop 在 stream 内部按 context 的 preparer 链完成 ATTACHMENT 投影与追加。 |
| `harness/session` | conversation 只**读** `message` 表：调 `session.MessageReadMapper.findMessagesByConversation` / `countMessagesByConversation` 等只读方法。**写方法（`insert` / `replaceForCompaction`）仍 session 私有**，conversation 不引用（ArchUnit 守护）。`session.MessageService.flush()` 由 loop 在回合边界调，conversation 不感知。 |
| `harness/permission` | conversation 调 `ConfirmationTokenService.issue / verifyAndConsume`；token 形状由 `contracts.ConfirmationToken` / `TokenClaims` 承载。challenge 状态由本模块**直接写 Redis**（`infra/cache.CacheStore`），不再经 `permission` 的 `ChallengeChallengeRepository`。 |
| `infra/cache` | conversation 注入 `CacheStore` 写 challenge（多副本）；注入 `RedissonClient`（仅用于 turn 锁）。`conversation` 模块允许依赖 `infra/cache`（见 §十四依赖图）。 |
| `module/file` | conversation 调 `PackageReferenceResolver.listImages(packageId)` 展开素材包引用；不写 `asset_*` 表。 |
| `module/dag` | conversation 调 `PendingPlanMapper.findById` 读 proposal；**CAS 路径**通过 `PendingPlanService.confirm(planId, taskId)` 委托（数据库 `UPDATE ... WHERE status='PENDING'`）；不直接调 mapper 的 update。生成式路径执行通过 `TaskCommandService.createAndEnqueue(...)`。 |
| `module/imagegen` | conversation 调 `ImagegenConfirmationSupport` 对 `IMAGEGEN` 提案重算 payloadHash / expectedCount；生成式执行仍通过 `TaskCommandService` 触发。conversation 不调用 imagegen 的 pending-plan mapper，也不实现 imagegen port。 |
| `module/task` | conversation 调 `TaskService.cancelTask(taskId)` 校验归属并触发取消（与 `module/task.md` 对齐）；不直连 `process_task` 表。 |
| `common.progress` | conversation 注入 `ProgressNotifier` SPI 推送进度；不直连 `SimpMessagingTemplate` 或 Redis pub/sub。`StompProgressNotifier` 由 app 层 `@Primary` 实现注入。 |
| `harness/hooks` / `harness/tools` | conversation 不直接订阅或调用；loop 在回合内派发；本模块仅通过 SSE 帧透出 loop 的 hook 影响。 |
| `infra/ai` | conversation 不依赖具体 provider 适配器；只 import provider-neutral 接口（如 `ModelStreamEvent`）作为 `AgentEvent` 类型参考；不直接 import `OpenAiModelClient` / `AliyunModelClient`。 |
| `infra/storage` | conversation 调 `ObjectStorage.upload(key, bytes)` 写用户上传图片；调 `ObjectStorage.generatePresignedGetUrl(key)` 给前端预览/下载链接（带 TTL）。 |
| `infra/cache` | conversation 调 `RedissonClient.getLock(...)` 实现会话级锁；调 `RedisTemplate.opsForValue()` 写 `cancel:task:*` 标志位。 |
| `common` | 所有 controller 抛出的异常经 `ErrorNormalizer` 归一化为 `PixFlowException`；文本（错误消息、agent message）经 `Sanitizer` 脱敏；分页响应统一 `PageResponse<T>` 形态。 |

**关键不变量**：

1. `message` 表写者唯一——本模块经 `context → TranscriptPort → session` 写穿透，**不**直连 `MessageWriteMapper`；
2. `module/dag.PendingPlan` 写者唯一（DAG handler + dag 的 `PendingPlanPortAdapter`），本模块只读，不实现 `contracts.proposal.PendingPlanPort`；
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
    turn-executor:
      max-concurrency: 16          # 单实例同时运行的 Agent 回合上限
      keep-alive: 60s              # 空闲 worker 回收时间；SynchronousQueue 不排队
    lock:
      # 故意不再暴露 ttl / watchdog-interval：会话级锁走 Redisson 全局
      # `Config.lockWatchdogTimeout` 自动续期，业务侧不需配。
      # 老 application.yml 里的 lock.ttl=60s 字段保留为 @Deprecated，新部署应删除。
      wait-time: 2s                # 拿不到锁的等待上限（极端并发下快速失败）
    confirmation:
      batch-threshold: 50          # 大批量二次确认阈值（张数）
      challenge-ttl: 5m            # challenge 有效期（Redis TTL）
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
      topic-prefix: /topic/task-progress  # 进度频道前缀（前端订阅路径）
```

> `lockWatchdogTimeout` 的真正源头是 `infra/cache` 的 `RedissonConfig`，由运维侧统一配（默认值 30s），不再在本模块配置里暴露。

默认值（生产级）：

- `lock.wait-time = 2s`：极端并发下快速失败而非无限等待（HTTP 503 `LOCK_ACQUISITION_FAILED`）。
- `turn-executor.max-concurrency = 16`：超过上限立即 HTTP 503 `TURN_CAPACITY_EXCEEDED`，不提交半截 SSE。
- `batch-threshold = 50`：与 `design.md §九.5` "大批量二次确认" 范围一致。
- `token-ttl = 10m`：足够用户在前端完成二次确认。
- `challenge-ttl = 5m`：足够用户在 ChallengeDialog 输入答案；过期后必须重新发起 `/challenge`。

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
| `TURN_CAPACITY_EXCEEDED` | DEPENDENCY | RETRY | 503 | 有界回合执行器满载，SSE 尚未提交 |
| `ATTACHMENT_INVALID` | VALIDATION | SKIP | 400 | 附件 MIME / 大小 / 解析失败 |
| `PACKAGE_REFERENCE_INVALID` | BUSINESS | NONE | 404 | 素材包引用解析失败（packageId 不存在 / 解压未完成） |

> 通用错误（dependency / validation / internal）走 `common.ErrorCode` 的既有码（`DEPENDENCY` / `VALIDATION` / `INTERNAL`）。

### 15.2 降级矩阵

| 失败场景 | 降级行为 | 影响范围 |
|---|---|---|
| Redis 锁不可用 | 拒绝回合（503 LOCK_ACQUISITION_FAILED），不进入 loop | 用户需重试 |
| Redis 取消标志位写失败 | 返回 503，用户可重试取消 | task 不会立即停，最终会由 `process_task.status` 收尾 |
| WebSocket 推送失败 | 静默重试 3 次（指数退避），仍失败记日志（不阻断 SSE 流） | 进度推送延迟；任务继续执行 |
| 历史查询超时（MySQL 慢） | 返回部分结果 + `nextCursor`，前端继续翻页 | 不阻断对话 |
| 附件上传到 MinIO 失败 | 返回 502，前端重试 | 当次附件丢失，需重传 |
| SSE 心跳发送失败 | 视为 transport failure，触发 CLIENT_DISCONNECTED 协作取消 | 模型/工具停止后释放锁 |
| 回合执行器满载 | 返回 503 `TURN_CAPACITY_EXCEEDED`，不创建已提交 SSE、不运行 worker | 客户端可稍后重试 |
| DagValidator 在 confirm 端点失败 | 返回 400，前端提示"请重新生成方案" | 用户需重新发消息让 Agent 生成新提案 |

### 15.3 SSE 错误帧与 controller 异常处理

错误出口由“响应是否已提交”分界：

- 准备阶段和 executor rejection：仍是普通 HTTP，由全局 handler 输出 `ApiResponse { success=false, code, message, details, traceId }`。
- SSE 已打开后的业务失败：session 推一次 `event:error`，随后普通 `emitter.complete()`；不进入 Servlet ERROR dispatcher。
- 客户端断开/timeout：通道不可写，只做协作取消和资源收口，不发送二次错误帧。
- 不可恢复异常（DB 断连 / Redis 雪崩 / NPE 逃逸）：经 `ErrorRecorder.record` 落盘 + 归一化为 `INTERNAL` 类 HTTP 500。

---

## 十六、可观测

### 16.1 Micrometer 指标（最小集）

由 `ConversationAutoConfiguration` 注入 `MeterRegistry` 自动暴露，不经 `harness/eval`（`eval` 模块聚焦回合级 trace，会话级运行时指标由本模块自身出）：

- `pixflow.conversation.create{result=ok|error}` / `conversation.list` / `conversation.archive`：CRUD 健康度。
- `pixflow.conversation.turn{result=completed|error|aborted}` + 回合时长计时器：用户回合健康度。
- `pixflow.conversation.lock.wait` 计时器 + `lock.acquired` 计数器：锁获取延迟与频率（高说明会话长）。
- `pixflow.conversation.sse.active`：当前活跃 SSE 流数（gauge）。
- `pixflow.conversation.sse.terminated{reason}`、`pixflow.conversation.sse.late_write`、`pixflow.conversation.sse.executor_rejected`：终止原因、终态后写入和容量拒绝。
- `pixflow.conversation.confirmation{result=challenge|direct_token|token_invalid|challenge_expired}`：确认 REST 调用分布。
- `pixflow.conversation.task.cancel{result=ok|not_found|already_terminal}`：取消分布。
- `pixflow.conversation.attachment.upload{result=ok|too_large|invalid_mime|minio_error}` + size 分布：附件健康度。

### 16.2 trace 责任

本模块**不自记 trace**（不调 `eval.TraceRecorder`）。回合级 trace 由 `harness/loop` 持有 `TurnTrace` 句柄；`AgentEvent` 的 `metadata["traceId"]` 由 loop 注入并由 `SseAgentEventSink` 透传到 SSE 帧；前端用该 `traceId` 即可在 `agent_trace` 表查到完整上下文。

业务级 trace（如"用户在历史查询端的查询"）不进入 `eval.trace`——这是非 Agent 操作；如未来需要，由本模块自建 `conversation_trace` 表（暂不考虑，见 [十八](#十八暂不考虑)）。

### 16.3 结构化日志

- Session 日志包含 `turnId` / `conversationId` / `ownerUserId` / termination reason / response committed；拿到 traceId 后可附加。禁止记录 prompt、token、附件内容或 provider 原始 body。
- Application 日志：DEBUG 级打印锁获取/释放、confirm 端点决策、WebSocket 推送结果。
- 错误日志：`ErrorRecorder.record` 自动落盘，含归一化 category / safeMessage / stacktrace（脱敏后）。

---

## 十七、测试策略

### 17.1 单元测试（每个 service / collector / mapper / lock 一组）

- **会话 CRUD**：`ConversationServiceTest` 覆盖开新会话 / 列分页 / 软删除 / 标题校验；用 H2 嵌入式 MySQL + MyBatis-Plus。
- **附件收集**：`AttachmentCollectorTest` 覆盖"用户上传 + 素材包引用 + 空附件 / 素材包不存在 / 素材包为空"四场景。
- **附件映射**：`AttachmentMapperTest` 断言 attachment 列表→ `loop.Attachment` 与 `context.Message(role=ATTACHMENT)` 转换的字段映射正确（`attachmentId` / `sourceRef` / `metadata.attachmentType` 等）。
- **会话级锁**：`ConversationLockTest` 用嵌入式 Redis（Testcontainers 或 `embedded-redis`）跑：
  - 同会话两次 POST 顺序串行（第二请求阻塞等第一回合结束）；
  - 不同会话并行（不阻塞）；
  - **看门狗续期**：在 `lockWatchdogTimeout` 内持续打心跳，断言连接不释放；
  - 看门狗故障兜底：mock 模拟续期线程被 kill，`lockWatchdogTimeout` 到期后锁自动释放（验证确实不传 leaseTime）；
  - 同会话跨节点获取（Testcontainers 多实例 Redis）。
- **SSE 适配与生命周期**：`SseAgentEventSinkTest` 只测 frame 投影；`SseTurnSessionTest` 覆盖正常完成、业务错误、disconnect/timeout 竞态、executor rejection、启动前/运行中取消、重复终止和锁释放顺序。
- **历史查询**：`HistoryQueryServiceTest` 用 `MessageReadMapper` 替身（fake）覆盖"全量 / 分页 clamp / page 边界"。
- **二次确认 challenge**：`ConfirmationServiceTest` 用 `CacheStore` 替身覆盖：
  - 低阈值 `/challenge` 返回 `needChallenge=false + token=null`；
  - 高阈值 `/challenge` 写入 Redis；
  - 高阈值 `/submit` 答案错误 → 400；过期 → 410；
  - **CAS** 竞争：mock `PendingPlanService.confirm` 抛 `DAG_PLAN_ALREADY_CONFIRMED`，assert 重读最新 taskId 给前端幂等响应。
- **prepare/commit**：`TurnPreparationServiceTest` 断言 owner 校验先于附件读取、锁后失败释放、成功不提前释放、重复 close 幂等；`TurnLockHandleTest` 验证跨线程按原 owner id 解锁。

### 17.2 端到端测试

- **`MessageControllerE2ETest`**：用 Testcontainers（MySQL + Redis + MinIO + RocketMQ + Qdrant），跑完整"开新会话 → POST /messages → 收到 assistant_delta 帧 → 收到 tool_result 帧（如 Agent 调用 search） → 收到 completed 帧 → 流关闭"链路；断言 `session.message` 表新增 message 条数与 SSE 事件一致；归档会话发消息返回 410；SSE `complete()` 触发后 turn 锁才释放（用 probe 验证）。
- **`ConfirmationE2ETest`**：构造 `pending_plan` 行（`expectedCount=10` / `=100`）、调 `/challenge` 端点：
  - `10` 场景：返回 `needChallenge=false + token=null`，前端调 `/submit` 后服务端签发 token 并完成 cas；
  - `100` 场景：返回 `challenge` 字段；
  - `/submit` 携带错误答案返回 400；
  - `/submit` 携带正确答案返回 taskId，并断言 `module/task` 创建 `process_task` 入 RocketMQ；
  - 二次提交同 token 返回 401；
  - **多副本 scenario**：用 Testcontainers 起 2 个 node 模拟同一 Redis，A node 写 challenge，B node 的 submit 能命中。
- **`CancellationE2ETest`**：构造运行中 task，POST `/cancel` 端点，断言 Redis `cancel:task:{taskId}` 已写、task worker 在下一个工作单元优雅停、WebSocket 推送 `status=CANCELLED` 帧。
- **`ConversationEndToEndIT`**（阶段 7 收尾）：跑"开新会话 → 发消息 → 流式接收 → 提交 DAG 提案 → challenge → submit → task 执行 → 进度推送 → 历史查询"全链路。

### 17.3 ArchUnit 守护（生产级硬约束）

`ConversationArchitectureTest` 集中断言：

1. **唯一写者守护**：`com.pixflow.module.conversation..` 不引用 `MessageWriteMapper`（任何含 `insert` / `replaceForCompaction` 写方法的 mapper）。
2. **不组装 prompt 文本**：扫描源文件 `import` 不出现 `ToolDescriptor` 等类型；不持有 `ToolRegistry`。
3. **不写 trace**：不调 `TraceRecorder`。
4. **不写 pending 表**：不引用 `PendingPlanMapper` 的写方法（`updateStatus / expireOverdue / expireByOldSchema`），不允许出现 `implements PendingPlanPort`；CAS 路径必须经 `PendingProposalRepository.markConfirmed` → `PendingPlanService.confirm`。
5. **SSE / 锁顺序**：扫描 `MessageController.submit` lambda 内语句顺序——`emitter.complete()` 必须先于 `handle.close()`（用 Spoon/ASM 字节码断言）。
6. **不引入 Reactor 依赖**：扫描 `pom.xml` 不出现 `spring-boot-starter-webflux`；源代码无 `Mono` / `Flux` 使用。

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
| 「附件关联」 | V1 瘦版：`attachments[]` 只接收具体 `UPLOAD_IMAGE`；素材包只走顶层 `packageId` 并在后端展开为内部 `PACKAGE_REFERENCE`；ATTACHMENT 投影在 context 侧 `AttachmentContextPreparer` |
| 「对 message 表只读查询」 | `HistoryQueryService` 调 `session.MessageReadMapper` 只读接口，写方法仍 session 私有 |
| 「写入经 session」 | 用户消息经 `context.MessageStore.appendUser` → `TranscriptPort` → `session.TranscriptService.append` 写穿透 |

新增的细化（design.md 未显式给出，本模块设计补充）：

- **会话级锁** `lock:turn:{conversationId}` 是 conversation 模块的责任（design.md §五 隐含但未细化）。
- **确认 REST 边界**（challenge + submit 两个端点）由 conversation 拥有；design.md §6.3 仅描述"确认边界在工具外"，本模块给出具体端点形态。
- **取消 / 进度**入站归 conversation，task 模块通过 Redis pub/sub / RocketMQ topic 解耦。
- **ATTACHMENT 投影**责任在 `harness/context` 的 `AttachmentContextPreparer`，conversation 只负责把 attachment 写到 message 表。

---

## Revision Notes

2026-07-10 / Codex: 重构 Agent SSE 生命周期为 prepare/commit 两阶段与单一 `SseTurnSession` 所有权；新增有界即时拒绝 executor、公共 cancellation token 传播、`CANCELLED` trace 语义、跨线程 Redisson owner 解锁、HTTP 503 容量错误和终态指标。已提交流的业务错误改为 `event:error + complete()`，disconnect/timeout 只取消，不触发 Servlet 异常完成分派。

- 2026-06-30 / Kiro: 新建 `docs/design-docs/module/conversation.md`。确立 conversation 模块为生产级 web 边界层，按 Wave 4 排位落地。固化全部 9 条决策（会话级锁 Redisson 短 TTL + 看门狗 / 确认 REST 双端点 challenge+submit / 附件 V1 瘦版仅 UPLOAD_IMAGE 与 PACKAGE_REFERENCE / ATTACHMENT 投影在 context 侧 / 素材包弱绑定多对话复用 / V1 不做 SSE 重连 / Spring MVC 同步不引入 WebFlux / 历史查询 V1 只给全量时间线 / 附件统一走素材包）。明确 conversation 与 `harness/loop` / `harness/session` 的边界——不写 `message` 表、不写 `pending_*` 表、不组装 prompt、不评估权限，由 ArchUnit 守护。配套落地计划见 `docs/design-docs/exec-plans/conversation-module-implementation-plan.md`。

- 2026-07-07 / Codex: 更新 pending-plan 所有权：DAG 与 imagegen 提案共用 `module/dag` 的 `pending_plan` 表；`PendingPlanPort` 契约在 `contracts.proposal`，唯一生产实现由 dag 提供。conversation 只读 `pending_plan` 并编排确认 REST，不再实现 `PendingPlanPort`，也不再描述独立 `PendingImagegenPlan` 表。

- 2026-07-09 / Codex: 按 conversation 安全生命周期重构计划同步实现状态：conversation 表新增 `owner_user_id` 与 owner 维度索引，controller 入口统一接入 `@CurrentUser AuthPrincipal`，service 删除无 owner 的公开读写方法；confirmation challenge 改为通过 `CacheStore.consume` 原子消费并校验 proposal/conversation 绑定，确认顺序改为 `PENDING -> CONFIRMING -> createAndEnqueue -> CONFIRMED(taskId)`；SSE worker 不再捕获 fatal `Throwable`，executor 提交失败和 heartbeat race 均有清理路径；附件 metadata 统一删除 null value 并拒绝复杂对象，历史分页增加 offset 溢出校验。
