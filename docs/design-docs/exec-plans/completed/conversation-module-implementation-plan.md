# 实现 module/conversation 业务出口（REST/SSE/确认端点 + 附件瘦版 + 会话级锁 + 历史查询 + 进度入站）

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文只规划 `module/conversation` 的完整生产级实现，不实现 Agent Prompt 组装（Wave 5 agent 模块）、不实现 Agent 级工具 handler（Wave 3 已在 harness/tools 中实现）、不引入 maxTurns、不做跨进程的可靠 SSE 重连、不把 conversation 变成主循环拥有者、不让 conversation 直接写 `message` 表。

## Purpose / Big Picture

完成本计划后，PixFlow 会拥有一个生产级的 `module/conversation` 业务出口层。它把用户与 Agent 决策回合之间的所有 HTTP/SSE 边界收口在 Spring MVC 控制器之后，把"会话生命周期 + 用户消息入站 + LLM token 流式接出 + 素材包附件 + 待确认提案执行闸门 + 历史展示 + 任务进度入站"组装成一个可被前端消费的端点集合。conversation 模块本身**不**驱动 think-act-observe 主循环（那是 `harness/loop`）、**不**持久化 message 行（那是 `harness/session` 唯一写者）、**不**组装 prompt、**不**评估权限——它只是把 web 层会话/回合语义与 harness 层协议之间的"粘合"做干净。

完成本计划后运行 `mvn -pl pixflow-conversation -am test` 应看到：会话级锁正确串行化同会话回合、用户消息经 `context.MessageStore.appendUser` + `appendAttachments` 写穿透到 `session` 落 MySQL、`SseAgentEventSink` 把 `loop.AgentEvent` 正确桥接为 SSE 帧、确认 REST 端点签发的 `ConfirmationToken` 能被 `permission.ConfirmationTokenService` 校验通过、二次确认挑战在阈值之上触发、WebSocket 进度入站把 task 进度推到前端、ArchUnit 守护证明 `com.pixflow.module.conversation..` 不直连 `message` 表的写路径、不直接调 `infra/ai` 的具体 provider。

## Progress

### 已完成

- [x] (2026-06-30 14:00+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、`docs/design-docs/design.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`、`docs/design-docs/harness/loop.md`、`docs/design-docs/harness/context.md`、`docs/design-docs/harness/session.md`、`docs/design-docs/harness/tools.md`、`docs/design-docs/harness/hooks.md`、`docs/design-docs/harness/eval.md`、`docs/references/attachment-architecture.md`、`docs/references/context-architecture.md`，确认 conversation 模块在依赖 DAG 中位于 Wave 4 `loop → agent` 之前的 `context → session → conversation` 末端，作为业务出口与 web 边界。
- [x] (2026-06-30 14:10+08:00) 阅读现有 `pixflow-common`、`pixflow-permission`、`pixflow-contracts`、`pixflow-tools`、`pixflow-context`、`pixflow-session`、`pixflow-eval`、`pixflow-hooks`、`pixflow-infra-storage`、`pixflow-infra-cache`、`pixflow-infra-ai`、`pixflow-module-file`、`pixflow-module-dag`、`pixflow-module-imagegen`、`pixflow-module-task` 源码，确认 `Message`/`MessageRole`/`MessageMetadata`/`Attachment`/`ToolExecutionContext`/`TurnTrace`/`ConfirmationToken`/`PendingPlan` 等已有类型与缺位 SPI 的现状。
- [x] (2026-06-30 14:20+08:00) 与用户对齐 7 个 plan 阶段必须钉死的实现选择：会话级锁采用 Redisson 短 TTL + 看门狗续期（A 方案）；确认 REST 端点采用两次端点 + challengeToken 形态（D2 方案 ①）；附件 V1 范围限定为素材包引用 + 用户上传图片，不做 @mention / file_state_cache / plan_mode attachment（D3 方案）；ATTACHMENT 投影放在 context 的 preparer 链最外层（D4 方案 A）；素材包-对话绑定采用弱绑定 + 多对话复用（D5 方案 ①）；V1 不做 SSE Last-Event-ID 重连（D6）；历史查询 V1 只给全量时间线（D7）。
- [x] (2026-06-30 14:30+08:00) 确认当前 `pixflow-conversation` Maven 模块尚未存在；本计划按生产级完整模块设计，不分 MVP 阶段。
- [x] (2026-06-30 14:30+08:00) 创建本 ExecPlan 初稿，按 `PLANS.md` 要求写清目的、机制、检索关键词、具体步骤和验收方式；本轮仅输出计划文档，不写 Java 代码。
- [x] (2026-06-30 14:45+08:00) 按用户要求确认落地路径在 `docs/design-docs/exec-plans/conversation-module-implementation-plan.md`，与 `loop-module-implementation-plan.md` 同级，作为 Wave 4 编排模块的实施蓝图。
- [x] (2026-06-30 12:44+08:00) 按本计划读取 `module/conversation.md` 与相关现有源码后，完成 `pixflow-conversation` 第一版可编译实现：conversation CRUD、历史只读查询、附件瘦版、会话级锁、SSE 适配、确认 challenge/submit 骨架、取消/进度桥、自动配置、app 依赖和最小测试护栏。
- [x] (2026-06-30 12:44+08:00) 验证 `mvn -pl pixflow-conversation -am -DskipTests compile` 通过；验证 `mvn -pl pixflow-conversation -am "-Dtest=ConversationServiceTest,AttachmentCollectorTest,ConversationArchitectureTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过（8 tests, 0 failures）。全量 `mvn -pl pixflow-conversation -am test` 曾因上游全模块测试链超过 120s 超时，未作为最终验收口径。

#### 阶段 1：更新设计与依赖地基
- [x] 新增 `pixflow-conversation` Maven 模块：在根 `pom.xml` 加 `<module>pixflow-conversation</module>` 与 dependencyManagement 条目；新建 `pixflow-conversation/pom.xml`，依赖 `pixflow-common` / `pixflow-permission` / `pixflow-contracts` / `pixflow-eval` / `pixflow-context` / `pixflow-session` / `pixflow-tools` / `pixflow-hooks` / `pixflow-loop` / `pixflow-state` / `pixflow-infra-ai` / `pixflow-infra-storage` / `pixflow-infra-cache` / `pixflow-module-file` / `pixflow-module-dag` / `pixflow-module-imagegen` / `pixflow-module-task`。
- [x] 在 `pixflow-session/.../MessageReadMapper.java` 暴露只读查询接口给 conversation：`findMessagesByConversation(conversationId, offset, limit)` / `findAttachments(conversationId)` / `countMessagesByConversation(conversationId)`，写方法（`insert` / `replaceForCompaction`）仍 session 私有（满足唯一写者守护）。
- [x] 在 `pixflow-context/.../MessageMetadata.java` 下扩展 `attachmentType` / `attachmentRef` / `attachedPackageId` / `attachmentId` 等键，供 attachment 投影与前端历史展示读取。
- [ ] 在 `pixflow-context/.../ContextEngine.java` 新增 `AttachmentContextPreparer`，作为 preparer 链最外层包装；该 preparer 把 `ATTACHMENT` 消息投影为 synthetic user/assistant/tool_result（参考实现 `attachment-architecture.md` 的 attachment 投影表）；投影结果**不写回 store**。
- [x] 复用现有 `pixflow-module-dag` 的 `pending_plan` / `PendingPlanMapper`，并在 conversation 中实现 `PendingPlanPortAdapter` 供 imagegen 的 `PendingPlanPort` SPI 使用；实际 schema 为 `id/tool_call_id/conversation_id/type/dag_json/payload_hash/schema_version/note/status/...`，不含原计划独立 `proposalId` / `actualCount` / `expectedCount` 字段。
- [x] 在 `pixflow-contracts` 新增 `ConfirmationChallenge` record 与 `ConfirmationChallengeStatus` enum（`PENDING` / `CONFIRMED` / `EXPIRED`）。
- [ ] 在 `docs/design-docs/harness/session.md` §十二 同步"对外暴露 `MessageReadMapper` 只读接口"的说明，并强调"写方法仍 session 私有"；在 §十五 与 conversation 的契约中新增"`message` 表历史查询走 session 暴露的只读接口"。
- [x] 在 `docs/design-docs/harness/context.md` §五 同步 `MessageMetadata` 新增 attachment 键；在 §四 同步 `AttachmentContextPreparer` 位置；在 §十七 暂不考虑保持"ATTACHMENT 仅做轻量直通投影"的约束。
- [ ] 在 `docs/design-docs/design.md` §五「Tool Registry」补充"Agent 工具调用 `submit_image_plan` / `submit_imagegen_plan` 后，`pixflow-module-dag` / `pixflow-module-imagegen` 把提案写入 `pending_plan` / `pending_imagegen_plan` 表，状态=PENDING，由 conversation 的确认 REST 端点读取并校验"；§十二模块划分补充 `module/conversation` 的细粒度职责段。
- [ ] 在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 的 Mermaid 图补 `state --> conversation` 与 `hooks --> conversation` 边（hooks 是 `runtimeScope` 来源，state 是进度数据源），把 Wave 4 任务清单的 `module/conversation` 行更新为"落地计划见 `conversation-module-implementation-plan.md`"。

#### 阶段 2：构建 conversation 模块骨架 + conversation 表 CRUD
- [x] 创建 `pixflow-conversation/.../api/ConversationController.java`：暴露 `POST /conversations`（开新会话）、`GET /conversations`（列会话，分页）、`GET /conversations/{id}`（会话详情）、`DELETE /conversations/{id}`（软删除，状态=ARCHIVED）。
- [x] 创建 `pixflow-conversation/.../app/ConversationService.java`：编排 `conversation` 表 CRUD；写入走 `ConversationMapper`（conversation 自己的 mapper，写自己的表，不碰 `message` 表）。
- [x] 创建 `pixflow-conversation/.../persistence/ConversationEntity.java`（`conversation` 表 PO，字段对齐 `design.md §13.1`）。
- [x] 创建 `pixflow-conversation/.../persistence/ConversationMapper.java`（MyBatis-Plus，仅 conversation 表的读写）。
- [x] 在 `pixflow-conversation/.../error/ConversationErrorCode.java` 暴露 `ConversationErrorCode` enum（`CONVERSATION_NOT_FOUND` / `CONVERSATION_ARCHIVED` / `CONVERSATION_TITLE_INVALID` 等）；未直接改 `pixflow-common` 的 `ErrorCode` 接口。
- [x] 测试：`ConversationServiceTest` 通过；ArchUnit 断言 `com.pixflow.module.conversation..` 不引用 `MessageWriteMapper`、不组装 tool prompt、不直接记录 eval trace。

#### 阶段 3：附件收集瘦版 + Attachment 投影
- [x] 创建 `pixflow-conversation/.../attachment/Attachment.java`：统一 attachment 形态 record（`attachmentId` / `type` / `sourceRef`（MinIO key）/ `packageId` / `metadata`）。
- [x] 创建 `pixflow-conversation/.../attachment/AttachmentCollector.java`：V1 瘦版，方法签名 `List<Attachment> collect(UserPrompt prompt, PackageBinding binding)`，只支持"用户上传图片 + 素材包引用"两类；不做 @mention / shared sources / file_state_cache。
- [x] 创建 `pixflow-conversation/.../attachment/AttachmentMapper.java`：把 `Attachment` 转为 `context.Message(role=ATTACHMENT, metadata={attachmentType, attachmentRef, attachedPackageId})`，并提供 loop attachment 转换入口；实际 append 仍由 `AgentTurnRunner` / `AgentLoop.stream` 按回合写穿透。
- [x] 在 `pixflow-module-file` 暴露 `PackageReferenceResolver` SPI（`packageId → MinIO prefix + 关联 image 列表`），conversation 通过它把素材包引用展开为 attachment 列表。
- [x] 测试：`AttachmentCollectorTest` 覆盖"图片附件 + 素材包引用"与 attachment → context message 映射；空附件与 session message 端到端仍待补。

#### 阶段 4：会话级锁 + 用户消息入站 + SSE 流式
- [x] 创建 `pixflow-conversation/.../lock/ConversationLock.java`：基于 Redisson 的 `lock:turn:{conversationId}` 短 TTL（默认 60s）实现 `tryLock`；Redisson leaseTime 负责崩溃 TTL 兜底。
- [x] 创建 `pixflow-conversation/.../api/MessageController.java`：暴露 `POST /conversations/{id}/messages`（用户消息入站，返回 `text/event-stream`）。
- [x] 创建 `pixflow-conversation/.../api/SseAgentEventSink.java`：实现 `loop.AgentEventSink`，把 `AgentEvent` 桥接为 SSE 帧（`event:` 字段=事件 type；`data:` 字段=JSON 序列化；`id:` 字段 V1 不用）。
- [x] 创建 `pixflow-conversation/.../app/TurnDispatchService.java`：编排用户消息入口——校验会话 → 收集 attachment → 取会话级锁 → 通过 `AgentTurnRunner` SPI 调用 Agent/loop → 完整回合后释放锁。`ContextEngine` rehydrate 与 prompt/tool schema 组装留给 Wave 5 agent runner，不在 conversation 内实现。
- [x] 创建 `pixflow-conversation/.../app/HistoryQueryService.java`：调 session 暴露的只读 `MessageReadMapper.findMessagesByConversation(conversationId, offset, limit)`；返回 `MessageView` 列表（包含压缩纪元 marker 行的可视化标记）。
- [x] 创建 `pixflow-conversation/.../config/ConversationProperties.java`：配置 SSE 超时（默认 5min）、锁 TTL（默认 60s）、history/attachment/confirmation/progress 默认配置。
- [ ] 测试：会话级锁单元测试覆盖"同会话回合串行 / 不同会话并行 / TTL 兜底崩溃"三场景；SSE 端到端测试断言"用户发消息 → 收到 `assistant_delta` 帧 → 收到 `completed` 帧 → 流关闭"。

#### 阶段 5：确认 REST 边界 + 二次确认挑战
- [x] 创建 `pixflow-conversation/.../proposal/PendingProposal.java`：统一待确认提案 record（`proposalId` / `conversationId` / `type`（`DAG` / `IMAGEGEN`）/ `payload` / `expectedCount` / `status` / `createdAt`）。
- [x] 创建 `pixflow-conversation/.../proposal/PendingProposalRepository.java`：调 `module/dag` 的 `PendingPlanMapper`（当前 DAG/imagegen 共用 `pending_plan` 表）包装为 `PendingProposal` 视图。
- [x] 创建 `pixflow-conversation/.../api/ConfirmationController.java`：暴露两个端点——
  - `POST /conversations/{id}/confirm/{proposalId}/challenge`：根据 `expectedCount` 与配置阈值（默认 50 张）比对，若需二次确认返回 `ConfirmationChallenge{challengeId, prompt}`；否则直接签发 `ConfirmationToken` 并触发执行。
  - `POST /conversations/{id}/confirm/{proposalId}/submit`：携带 `challengeAnswer` 调端点 1 校验；通过则签发 `ConfirmationToken`（绑定 proposalId + 操作类型）→ 调 `module/dag` 或 `module/imagegen` 的执行入口 → 标记提案状态=CONFIRMED；返回执行任务 id。
- [x] 创建 `pixflow-conversation/.../app/ConfirmationService.java`：编排 challenge 签发、token 签发、执行触发；实际使用现有 `permission.ConfirmationTokenService.issue(TokenClaims)`，challenge 状态 V1 暂存进程内 map，待 permission/Redis challenge store 接入后替换。
- [x] 创建 `pixflow-conversation/.../proposal/ProposalThreshold.java`：从 conversation 配置读取"大批量二次确认阈值"（默认 50 张）；与提案 `expectedCount` 比对决定是否触发 challenge。
- [ ] 测试：确认端点单元测试覆盖"低阈值直接签发 token / 高阈值返回 challenge / challenge 失败拒绝 / token 二次使用拒绝"四场景；端到端测试断言"DAG 提案确认后 `module/task` 创建 `process_task` 任务入 RabbitMQ"。

#### 阶段 6：取消 / 中断 + 进度入站
- [x] 创建 `pixflow-conversation/.../app/CancellationService.java`：暴露 `cancelTask(taskId)` 方法，调用现有 `module/task.TaskCommandService.cancel(CancelTaskCommand)`；task 模块内部负责取消语义。
- [x] 创建 `pixflow-conversation/.../api/CancellationController.java`：暴露 `POST /conversations/{id}/tasks/{taskId}/cancel` 端点，编排 `CancellationService`。
- [x] 创建 `pixflow-conversation/.../progress/ConversationProgressBridge.java`：通过 `common.ProgressNotifier` 转发为逻辑频道 `task-progress-{conversationId}-{taskId}`；实际 STOMP sink 复用 app 层 `StompProgressNotifier`。
- [ ] 测试：取消端点单元测试覆盖"同对话用户取消 / 跨对话用户取消拒绝 / task 已完成时取消拒绝"三场景；进度入站测试断言"task worker 写进度 → Redis pub/sub → WebSocket 推到前端"链路打通。

#### 阶段 7：与上游契约同步 + ArchUnit 守护 + 集成测试
- [x] 在 `pixflow-conversation/.../archunit/ConversationArchitectureTest.java`：用 ArchUnit 断言：
  - `com.pixflow.module.conversation..` 不直接调 `MessageWriteMapper`（任何写 `message` 表的 mapper）。
  - `com.pixflow.module.conversation..` 不组装 prompt/tool descriptor。
  - `com.pixflow.module.conversation..` 不直接记录 eval trace。
- [ ] 集成测试：在 `pixflow-conversation/src/test/java/.../integration/ConversationEndToEndIT.java` 用 Testcontainers MySQL + Redis + MinIO + RabbitMQ 跑端到端"开新会话 → 发消息 → 流式接收 → 提交 DAG 提案 → 二次确认 → task 执行 → 进度推送 → 历史查询"全链路。
- [x] 在 `docs/design-docs/module/conversation.md` 新建正式模块设计文档，把本计划中的所有决策固化。
- [ ] 在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 把 Wave 4 任务清单的 `module/conversation` 行标记为已完成。
- [ ] 在 `docs/design-docs/design.md` §十四 异步执行时序补充"conversation 提供 SSE 入口与确认 REST 边界"。

## Surprises & Discoveries

- Observation: OneCode 的 `AttachmentProjector` 放在 `services/context/` 内部 preparer 链最外层，与 conversation 解耦；PixFlow 的 conversation 模块**没有** ATTACHMENT 投影责任。
  Evidence: 见 `docs/references/attachment-architecture.md` §"接口设计"。
  Impact: 阶段 1 把 `AttachmentContextPreparer` 落地到 `pixflow-context`，conversation 只负责"收集 attachment + 调 `appendAttachments`"。

- Observation: `harness/tools.md §八.3` 明确"工具集零真实副作用、零令牌"，但 session 暴露的 `PendingPlanMapper` 与生图侧的 `PendingImagegenPlanMapper` 都要落到数据库中——这些是"提案"而非"执行"。
  Evidence: `design.md §6.3` "HITL 确认" + `tools.md §3.4/§3.5`。
  Impact: 阶段 1 把 `pending_plan` / `pending_imagegen_plan` 表的责任放到 `module/dag` / `module/imagegen`；conversation 只读不写这两张表，统一通过 `PendingProposalRepository` 视图读取。

- Observation: OneCode 的 `AttachmentCollector` 包含 shared sources（`BackgroundTaskNotificationSource`）与 file_state_cache，这些在 PixFlow V1 都不存在。
  Evidence: 见 `docs/references/attachment-architecture.md` §"AttachmentCollector" 与 `loop.md §十八` "暂不考虑"。
  Impact: V1 附件瘦版只支持"用户上传图片 + 素材包引用"两类；不预留 shared sources 接口位（避免过度设计）。

- Observation: Wave 5 agent 模块尚未落地，而 `AgentLoop.stream(...)` 仍要求调用方传入 systemPrompt 与 toolSchemas；conversation 文档明确禁止本模块组装 prompt。
  Evidence: `pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java` 的 `stream(prompt, attachments, sink, systemPrompt, toolSchemas)` 签名与 `module/conversation.md` §三边界不变量。
  Impact: 本轮新增 `AgentTurnRunner` SPI，由 conversation 负责 web / 锁 / SSE / attachment 边界，后续 agent 层实现该 SPI 并在其中构造 `AgentLoop` 与 prompt/tool schema。

- Observation: 当前 `pending_plan` 已由 `pixflow-module-dag` 落地，且 imagegen 侧已有 `PendingPlanPort` SPI，实际 schema 与本计划初稿不同：主键是 Long `id`，DAG 与 imagegen 共用 `pending_plan`，没有独立 `expectedCount` / `packageId` 字段。
  Evidence: `pixflow-module-dag/src/main/resources/db/migration/V1__create_pending_plan.sql` 与 `pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/port/PendingPlanPort.java`。
  Impact: 本轮 conversation 实现 `PendingPlanPortAdapter` 并把 proposalId 解释为 pending_plan.id 字符串；`packageId` 暂从 `note=packageId=...` 解析，`expectedCount` 暂为 0，完整大批量二次确认需后续补 schema 或 payload 解析。

- Observation: app 层已经存在 `StompProgressNotifier`，task 模块也已有 `TaskCommandService.cancel(...)`。
  Evidence: `pixflow-app/src/main/java/com/pixflow/app/progress/StompProgressNotifier.java` 与 `pixflow-module-task/src/main/java/com/pixflow/module/task/api/TaskCommandService.java`。
  Impact: conversation 的取消与进度入站复用现有抽象：取消调用 `TaskCommandService`，进度桥只发布逻辑频道，不重复实现 Redis 标志位或 STOMP 基础设施。

## Decision Log

- Decision: 会话级锁采用 Redisson 短 TTL（默认 60s）+ 看门狗续期（A 方案），不采用会话活跃状态长锁（B）或乐观锁（C）。
  Rationale: turn 内 LLM 调用是秒级，60s TTL 足够；看门狗续期处理长尾；崩溃由 TTL 自动兜底，避免 heartbeat 任务的额外复杂度。turn 自带超时由 `loop.max-output-recovery-limit` 等配置兜底。
  Date/Author: 2026-06-30 / Kiro

- Decision: 确认 REST 端点采用两次端点形态（`/challenge` + `/submit`），不是单端点自决（D2 方案 ①）。
  Rationale: 二次确认挑战与令牌签发语义分离，permission 侧 `ConfirmationTokenService` 已有"challengeToken"概念；两次端点让前端流程清晰，避免单端点"既要自决 challenge 又要签发 token"的语义耦合。
  Date/Author: 2026-06-30 / Kiro

- Decision: 附件 V1 范围限定为"素材包引用 + 用户上传图片"，不做 @mention / file_state_cache / plan_mode attachment / background_task_notification / todo_reminder（D3 方案）。
  Rationale: 这些机制在 PixFlow V1 没有对应需求（无项目级文件 workspace、无文件状态变更、无 plan_mode 概念、无后台任务通知源、无 todo 概念）。预留会让 `AttachmentCollector` 复杂度过高，违反"暂不考虑"约束。
  Date/Author: 2026-06-30 / Kiro

- Decision: ATTACHMENT 投影放在 `context` 的 preparer 链最外层（`AttachmentContextPreparer`），不在 conversation（D4 方案 A）。
  Rationale: 与参考实现 `attachment-architecture.md` 一致；projection 是模型调用前的转换，不应写在 conversation 的 append 路径；context 已有 preparer 链基础设施（DAG-projector / compaction 等），新增 `AttachmentContextPreparer` 接入成本最低。
  Date/Author: 2026-06-30 / Kiro

- Decision: 素材包与对话采用弱绑定（同一素材包可被多对话复用，D5 方案 ①）。
  Rationale: 与参考实现的多会话共享 source 模型一致；同一批素材可能用于多次尝试（不同 prompt / 不同处理方案）；强绑定会让用户每次新对话都重复上传。
  Date/Author: 2026-06-30 / Kiro

- Decision: V1 SSE 不做 `Last-Event-ID` 重连（D6 方案）。
  Rationale: SSE 断线 = 回合异常，前端按"该回合失败"处理，用户重新发消息。重连需要环形缓冲 + 事件幂等 + 顺序保证，复杂度极高，对 V1 核心价值（流式输出）无直接收益。
  Date/Author: 2026-06-30 / Kiro

- Decision: 历史查询 V1 只给全量时间线，不区分"压缩后活动链视图"（D7 方案）。
  Rationale: 前端展示"按时间线"是审计价值最高、实现最简单的视图；如果未来要做"模型当时看到什么"，可以单独补一个 `/conversations/{id}/active-chain` 端点。V1 不预留该接口，避免 YAGNI。
  Date/Author: 2026-06-30 / Kiro

- Decision: conversation 模块采用 Spring MVC 同步（`@RestController` + `SseEmitter`），不引入 WebFlux（Q1）。
  Rationale: 与全栈"阻塞主链路 + 回合内线程封闭"一致；loop / context / hooks / session / eval 都假设同步模型；WebFlux 会破坏跨模块的同步假设，且 SSE 在 Spring MVC 中已成熟支持。
  Date/Author: 2026-06-30 / Kiro

- Decision: 附件图片统一走 `module/file` 素材包，不在 conversation 维护独立的"对话内图片"桶（Q2）。
  Rationale: 避免分散存储；统一走素材包让 SKU 绑定 / 分组识别等 `module/file` 已有的能力复用；素材包可被多对话复用（弱绑定）。
  Date/Author: 2026-06-30 / Kiro

## Outcomes & Retrospective

截至 2026-06-30 12:44+08:00，本计划已完成一版可编译的 `pixflow-conversation` 基础实现，覆盖会话 CRUD、只读历史、附件瘦版、会话级锁、SSE 适配、确认 REST 骨架、取消/进度桥、自动配置与 app 依赖接入。该实现遵守三条核心边界：conversation 不写 `message` 表、不组装 prompt/tool schema、不直接记录 eval trace。

验证结果：

- `mvn -pl pixflow-conversation -am -DskipTests compile` 通过。
- `mvn -pl pixflow-conversation -am "-Dtest=ConversationServiceTest,AttachmentCollectorTest,ConversationArchitectureTest" "-Dsurefire.failIfNoSpecifiedTests=false" test` 通过，conversation 侧 8 个测试全绿。
- 全量 `mvn -pl pixflow-conversation -am test` 曾因上游全模块测试链超过 120s 超时；未发现 conversation 编译失败，但完整端到端验收仍待后续执行。

仍需收尾的生产级事项：

- `AttachmentContextPreparer` 仍未按原计划在 context preparer 链最外层实现，当前仅补了 metadata 键与 attachment 映射。
- `AgentTurnRunner` 需要 Wave 5 agent 层提供真实实现，否则默认 runner 会返回 `TURN_RUNNER_UNAVAILABLE`。
- `pending_plan` schema 与原计划不一致，`expectedCount` / `packageId` 的可靠来源需要后续补齐，才能完整兑现大批量二次确认与 task 创建语义。
- 会话锁、SSE、确认、取消、WebSocket 进度还缺端到端/集成测试。

## Context and Orientation

`module/conversation` 在 `module-dependency-dag-plan.md` 中位于 Wave 4 主循环 + 编排模块排位，与 `harness/loop`、`module/task` 并列。它的依赖链是 `infra/storage` → `infra/cache` → `infra/ai` → `harness/hooks` → `harness/eval` → `harness/context` → `harness/session` → `harness/permission` → `harness/tools` → `harness/loop` → `module/file` / `module/dag` / `module/imagegen` / `module/task` → `module/conversation`。conversation 模块本身**不**被任何业务模块依赖（它是 web 边界层），但被 `pixflow-app` 装配消费。

当前仓库已有 `pixflow-common`、`pixflow-contracts`、`pixflow-permission`、`pixflow-hooks`、`pixflow-eval`、`pixflow-context`、`pixflow-session`、`pixflow-tools`、`pixflow-infra-ai`、`pixflow-infra-storage`、`pixflow-infra-cache`、`pixflow-state`、`pixflow-module-file`、`pixflow-module-dag`、`pixflow-module-imagegen`、`pixflow-module-task`，并已创建 `pixflow-conversation` Maven 模块第一版实现。

术语定义：

- **conversation（会话）**：用户视角的对话单元，对应 `conversation` 表的一行。`conversation.id` 是 web 层语义 id，全局唯一。
- **turn（回合）**：用户一次发消息 = 一个 Agent 决策回合 = 一次 `loop.stream(...)` 调用，由 `eval.turnNo` 标识。
- **iteration（迭代）**：回合内部一次"调 LLM → 执行工具 → 回填"的循环单轮，由 `eval.recordInput` 计数。
- **ATTACHMENT role**：`context.MessageRole` 的一个值，标识消息是 durable attachment（图片/素材包引用），由 `context.AttachmentContextPreparer` 在模型调用前投影为 synthetic user/assistant/tool_result，不写回 store。
- **HITL 确认闸门**：所有触发真实副作用的动作（执行 DAG、生图、重跑）必须由用户在 web 层确认后触发；Agent 工具集零令牌，提案由 Agent 写入 `pending_plan` / `pending_imagegen_plan` 表，确认由 conversation 的 REST 端点完成。
- **会话级锁**：基于 Redisson 的 `lock:turn:{conversationId}` 短 TTL 锁，确保同会话回合串行执行，跨节点生效。

## Plan of Work

整体工作分 7 个阶段，按依赖顺序推进：

**阶段 1（更新设计与依赖地基）** 在不写 Java 代码的前提下，先把上游契约打通。这一阶段的关键产物是：(a) 根 `pom.xml` 添加 `pixflow-conversation` 模块；(b) `pixflow-session` 暴露 `MessageReadMapper` 只读接口；(c) `pixflow-context` 扩展 `MessageMetadata` 并新增 `AttachmentContextPreparer`；(d) `pixflow-module-dag` 与 `pixflow-module-imagegen` 新增 `pending_plan` / `pending_imagegen_plan` 表与 mapper；(e) `pixflow-contracts` 新增 `ConfirmationChallenge` 类型；(f) 上游设计文档（`design.md` / `harness/session.md` / `harness/context.md` / `module-dependency-dag-plan.md`）同步更新。

**阶段 2（conversation 表 CRUD）** 创建 `pixflow-conversation` Maven 模块骨架，实现 `conversation` 表的增删改查与软删除，建立 conversation 自己的 `ConversationMapper`（不碰 `message` 表）。这一阶段的产物是 `ConversationController` + `ConversationService` + `ConversationEntity` + `ConversationMapper` + `ConversationErrorCode`。

**阶段 3（附件收集瘦版）** 实现 `AttachmentCollector`（只支持"用户上传图片 + 素材包引用"）、`Attachment` record、`AttachmentMapper`（把 attachment 转为 `context.Message(role=ATTACHMENT)`）。同时让 `pixflow-module-file` 暴露 `PackageReferenceResolver` SPI。配合阶段 1 的 `AttachmentContextPreparer`，完成 attachment 的"收集 → 持久化 → 投影"全链路。

**阶段 4（会话级锁 + 用户消息入站 + SSE 流式）** 实现 `ConversationLock`（Redisson 短 TTL + 看门狗续期）、`MessageController`（POST 端点）、`SseAgentEventSink`（loop 事件 → SSE 帧）、`TurnDispatchService`（编排入口）、`HistoryQueryService`（只读 message 表）。这一阶段的产物是"用户发消息 → 流式接收 LLM 输出 → 历史查询"的最小可用闭环。

**阶段 5（确认 REST 边界 + 二次确认挑战）** 实现 `PendingProposal` / `PendingProposalRepository` / `ConfirmationController`（`/challenge` + `/submit` 两个端点）/ `ConfirmationService` / `ProposalThreshold`。调 `permission.ConfirmationTokenService.issueForChallenge` 与 `verify`。这一阶段的产物是"Agent 提案 → 用户确认 → 触发真实执行"的 HITL 闸门。

**阶段 6（取消 / 中断 + 进度入站）** 实现 `CancellationService` / `CancellationController` / `ConversationProgressBridge`（订阅 Redis pub/sub / RabbitMQ topic 进度事件，转 WebSocket 推到前端）。这一阶段的产物是"用户取消任务 + 实时接收任务进度"能力。

**阶段 7（与上游契约同步 + ArchUnit 守护 + 集成测试）** 加 ArchUnit 守护（不直连 message 表写路径、不依赖 provider 具体适配器、不组装 prompt），跑 Testcontainers 端到端集成测试，新建正式 `docs/design-docs/module/conversation.md`，更新 `module-dependency-dag-plan.md` 与 `design.md` 的相关段落。

## Concrete Steps

执行命令统一在仓库根目录 `D:\study\PixFlow` 下用 PowerShell 运行。

### 阶段 1 命令

    # 创建 Maven 模块目录
    New-Item -ItemType Directory -Force -Path pixflow-conversation
    
    # 在根 pom.xml 添加模块
    # （编辑根 pom.xml，加 <module>pixflow-conversation</module> 与 dependencyManagement 条目）

### 阶段 2 命令

    # 单元测试
    mvn -pl pixflow-conversation -am test
    
    # ArchUnit 守护测试
    mvn -pl pixflow-conversation -am test -Dtest=ConversationArchitectureTest

### 阶段 3 命令

    # 附件单元 + 端到端测试
    mvn -pl pixflow-conversation -am test -Dtest='Attachment*Test'

### 阶段 4 命令

    # 锁单元测试
    mvn -pl pixflow-conversation -am test -Dtest='*LockTest'
    
    # SSE 端到端测试（需要本地 redis + minio，可临时用 Testcontainers）
    mvn -pl pixflow-conversation -am test -Dtest='MessageControllerE2ETest'

### 阶段 5 命令

    # 确认端点单元 + 端到端测试
    mvn -pl pixflow-conversation -am test -Dtest='Confirmation*Test'

### 阶段 6 命令

    # 取消 + 进度测试
    mvn -pl pixflow-conversation -am test -Dtest='Cancellation*Test,ConversationProgress*Test'

### 阶段 7 命令

    # 集成测试（Testcontainers，需要 Windows docker 入口，见 design.md §4.1）
    mvn -pl pixflow-conversation -am test -Dtest='ConversationEndToEndIT'
    # Windows 本地用：
    $env:DOCKER_HOST = 'npipe:////./pipe/docker_engine'
    mvn -pl pixflow-conversation -am test -Dtest='ConversationEndToEndIT'

    # 完整模块测试
    mvn -pl pixflow-conversation -am test

## Validation and Acceptance

每个阶段都通过单元测试 + ArchUnit 守护 + 端到端测试三层验证。

**阶段 1 验收**：`mvn -pl pixflow-session -am test -Dtest=MessageReadMapperTest` 通过（暴露的只读接口能查能分页）；`mvn -pl pixflow-context -am test -Dtest=AttachmentContextPreparerTest` 通过（ATTACHMENT 投影行为正确）；`mvn -pl pixflow-module-dag -am test -Dtest=PendingPlanMapperTest` 通过（pending_plan 表 CRUD 正确）。

**阶段 2 验收**：`POST /conversations` 返回 200 + conversation id；`GET /conversations` 分页正确；ArchUnit 断言 `com.pixflow.module.conversation..ConversationMapper` 引用了 `MessageEntity` 时测试失败（消息表写路径被守护）。

**阶段 3 验收**：构造"用户上传 1 张图片 + 关联 1 个素材包（5 张图）"的输入，断言 `AttachmentCollector.collect` 返回 6 个 attachment；`AttachmentMapper` 把这些 attachment 转为 6 条 `Message(role=ATTACHMENT)`；调 `appendAttachments` 后 `session.message` 表新增 6 行（`role=ATTACHMENT`）；`AttachmentContextPreparer` 在 preparer 链最外层把 6 行 ATTACHMENT 投影为 synthetic user/assistant/tool_result 消息。

**阶段 4 验收**：用两个并发线程模拟"同会话两次 POST /messages"，断言第二个请求在第一个回合完成（或失败）后才开始执行；SSE 端到端测试断言收到的事件顺序为 `assistant_delta*` → `tool_*`（如有）→ `completed`，且最终 `session.message` 表的 message 条数与 SSE 事件数一致；`HistoryQueryService` 能查到该会话的所有 message（含 USER/ASSISTANT/TOOL_RESULT/ATTACHMENT）。

**阶段 5 验收**：构造 `pending_plan` 一条（`expectedCount=10`，阈值 50），POST `/challenge` 端点直接返回 `ConfirmationToken`；构造 `pending_plan` 一条（`expectedCount=100`，阈值 50），POST `/challenge` 端点返回 `ConfirmationChallenge{challengeId, prompt}`；POST `/submit` 携带正确答案返回 token，携带错误答案返回 400；token 二次使用返回 401；token 校验通过后 `module/task.process_task` 入库 + RabbitMQ 消息发出。

**阶段 6 验收**：构造一个 task（status=执行中），POST `/cancel` 端点成功写 `cancel:task:{taskId}`；task worker 在下一个工作单元检查到标志位后优雅停；`ConversationProgressBridge` 订阅到 task 进度事件后正确通过 WebSocket 推到前端订阅频道（用嵌入式 STOMP 客户端验证）。

**阶段 7 验收**：`mvn -pl pixflow-conversation -am test` 全绿；`ConversationEndToEndIT` 跑通端到端"开新会话 → 发消息 → 流式接收 → 提交 DAG 提案 → 二次确认 → task 执行 → 进度推送 → 历史查询"全链路；ArchUnit 全部守护通过；正式 `docs/design-docs/module/conversation.md` 创建完成；`module-dependency-dag-plan.md` 与 `design.md` 的相关段落同步更新。

## Idempotence and Recovery

每个阶段都设计为可幂等执行：

- 阶段 1 的文档修改全部是"新增段落"或"更新现有段落"，不删除既有内容；Maven 模块添加是标准 Maven 行为。
- 阶段 2-6 的代码添加遵循 Spring bean 装配惯例，重启应用即可重试；测试用 H2 / Testcontainers，不污染生产数据。
- 阶段 7 的 ArchUnit 测试加 `--fail-if-no-tests` 守护；集成测试用 Testcontainers 的 `withReuse(true)` 复用容器，重跑成本低。

rollback 路径：

- 阶段 1-6 任一阶段失败：删除该阶段新增的 Java 文件，保留根 `pom.xml` 中的模块声明（用于下一轮重试），git stash 该阶段的文档修改。
- 阶段 7 失败：保留所有单元测试与 ArchUnit 守护，集成测试可暂时 `@Disabled` 并在 issue 中跟踪。

## Artifacts and Notes

### 关键产物清单

执行完毕后，仓库将新增以下文件：

- `pixflow-conversation/pom.xml`（Maven 模块定义）
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/ConversationController.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/MessageController.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/ConfirmationController.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/CancellationController.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/SseAgentEventSink.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/app/ConversationService.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/app/TurnDispatchService.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/app/HistoryQueryService.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/app/ConfirmationService.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/app/CancellationService.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/lock/ConversationLock.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/attachment/Attachment.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/attachment/AttachmentCollector.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/attachment/AttachmentMapper.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/proposal/PendingProposal.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/proposal/PendingProposalRepository.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/proposal/ProposalThreshold.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/progress/ConversationProgressBridge.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/persistence/ConversationEntity.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/persistence/ConversationMapper.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/config/ConversationProperties.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/config/ConversationAutoConfiguration.java`
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/error/ConversationErrorCode.java`
- `pixflow-conversation/src/test/java/com/pixflow/module/conversation/archunit/ConversationArchitectureTest.java`
- `pixflow-conversation/src/test/java/com/pixflow/module/conversation/integration/ConversationEndToEndIT.java`
- 各类单元测试文件（`Attachment*Test`, `*LockTest`, `MessageControllerE2ETest`, `Confirmation*Test`, `Cancellation*Test`, `ConversationProgress*Test`）

修改的文件：

- `pom.xml`（根 POM）：添加 `<module>pixflow-conversation</module>` 与 dependencyManagement 条目
- `pixflow-session/.../MessageReadMapper.java`：暴露只读查询接口
- `pixflow-context/.../MessageRole.java`：ATTACHMENT 分支扩展
- `pixflow-context/.../ContextEngine.java`：新增 `AttachmentContextPreparer`
- `pixflow-module-dag/.../PendingPlanMapper.java`：新增（pending_plan 表读写）
- `pixflow-module-imagegen/.../PendingImagegenPlanMapper.java`：新增（pending_imagegen_plan 表读写）
- `pixflow-contracts/.../ConfirmationChallenge.java`：新增
- `pixflow-permission/.../ConfirmationTokenService.java`：新增 challengeToken 签发与校验方法
- `pixflow-module-file/.../PackageReferenceResolver.java`：新增 SPI

新建的文档：

- `docs/design-docs/module/conversation.md`：正式模块设计文档

更新的文档：

- `docs/design-docs/design.md`：§五 Tool Registry 补充 pending 提案流、§十二 补充 conversation 模块细粒度职责、§十四 补充 conversation SSE 入口
- `docs/design-docs/harness/session.md`：§十二 与 §十五 同步暴露只读接口
- `docs/design-docs/harness/context.md`：§五 与 §四 同步 AttachmentContextPreparer 与 MessageMetadata 扩展
- `docs/design-docs/exec-plans/module-dependency-dag-plan.md`：Mermaid 图补 `state --> conversation` 与 `hooks --> conversation` 边；Wave 4 任务清单标记 conversation 完成

### 依赖清单

`pixflow-conversation/pom.xml` 必须依赖：

- `pixflow-common`（错误处理 / 分页 / 通用响应）
- `pixflow-contracts`（确认令牌 / 共享契约）
- `pixflow-permission`（ConfirmationTokenService / PermissionPolicy）
- `pixflow-eval`（TurnTrace 句柄不直接持有，由 loop 持有）
- `pixflow-context`（MessageStore / ContextEngine / AttachmentContextPreparer）
- `pixflow-session`（MessageReadMapper 只读接口）
- `pixflow-tools`（ToolExecutionContext 构造参考，但不直接调）
- `pixflow-hooks`（RuntimeScope / HookEvent 类型）
- `pixflow-loop`（AgentLoop.stream / AgentEventSink）
- `pixflow-state`（ProgressNotifier）
- `pixflow-infra-ai`（provider-neutral 接口）
- `pixflow-infra-storage`（MinIO 对象存储）
- `pixflow-infra-cache`（Redisson 锁 + cancel 标志位）
- `pixflow-module-file`（PackageReferenceResolver）
- `pixflow-module-dag`（PendingPlanMapper / DagValidator）
- `pixflow-module-imagegen`（PendingImagegenPlanMapper）
- `pixflow-module-task`（process_task 创建入口）

## Interfaces and Dependencies

### conversation 模块对外暴露的核心接口

`pixflow-conversation/.../api/ConversationController.java` 暴露：

    POST /conversations
    Body: { title?: string, packageId?: string }
    Response: 200 { conversationId, title, createdAt, packageId? }

`pixflow-conversation/.../api/MessageController.java` 暴露：

    POST /conversations/{conversationId}/messages
    Content-Type: multipart/form-data 或 application/json
    Body: { prompt: string, attachments?: [{ attachmentId, type, sourceRef, packageId? }], packageBinding?: { packageId } }
    Response: 200 text/event-stream（SSE 帧序列）
    SSE 事件: assistant_delta / tool_call_ready / tool_started / tool_result / transition / completed
    每帧格式: event: <type>\ndata: <json>\n\n

`pixflow-conversation/.../api/ConfirmationController.java` 暴露：

    POST /conversations/{conversationId}/confirm/{proposalId}/challenge
    Response: 200 { needChallenge: boolean, challenge?: { challengeId, prompt }, token?: string }

    POST /conversations/{conversationId}/confirm/{proposalId}/submit
    Body: { challengeAnswer?: string }
    Response: 200 { taskId, status: 'PENDING' | 'RUNNING' }

`pixflow-conversation/.../api/CancellationController.java` 暴露：

    POST /conversations/{conversationId}/tasks/{taskId}/cancel
    Response: 200 { cancelled: true }

### 内部 SPI（依赖倒置约定）

`pixflow-conversation/.../app/TurnDispatchService.java` 依赖：

    void stream(String conversationId, UserPrompt prompt, List<Attachment> attachments, AgentEventSink sink);
    // 内部实现：ConversationLock → ContextEngine rehydrate → appendUser + appendAttachments → loop.stream

`pixflow-conversation/.../app/ConfirmationService.java` 依赖：

    Optional<ConfirmationChallenge> requestChallenge(PendingProposal proposal, ProposalThreshold threshold);
    Optional<ConfirmationToken> confirmChallenge(String challengeId, String answer);
    void executeProposal(ConfirmationToken token);  // 调 module/dag 或 module/imagegen 执行入口

`pixflow-conversation/.../lock/ConversationLock.java` 接口：

    public interface ConversationLock {
        Optional<AutoCloseable> tryLock(String conversationId, Duration ttl);
        // 返回的 AutoCloseable 在 close() 时释放锁；TTL 由看门狗续期
    }

`pixflow-conversation/.../attachment/AttachmentCollector.java` 接口：

    public interface AttachmentCollector {
        List<Attachment> collect(UserPrompt prompt, PackageBinding binding);
    }

`pixflow-conversation/.../progress/ConversationProgressBridge.java` 接口：

    public interface ConversationProgressBridge extends ProgressNotifier {
        void onProgress(String conversationId, String taskId, ProgressEvent event);
        // 把 task 进度转 WebSocket 推到前端订阅频道
    }

### 依赖模块暴露给 conversation 的关键接口

`pixflow-session/.../MessageReadMapper.java` 必须暴露：

    List<MessageView> findMessagesByConversation(String conversationId, Pageable pageable);
    MessageView findMessageById(String conversationId, String messageId);
    List<MessageView> findAttachments(String conversationId);
    // 注意：insert / replaceForCompaction 等写方法仍 session 私有，conversation 不应引用

`pixflow-context/.../ContextEngine.java` 必须新增：

    AttachmentContextPreparer getAttachmentPreparer();
    // 或：ContextEngine.buildForModel 内部已包含 AttachmentContextPreparer，无需外部引用

`pixflow-contracts/.../ConfirmationChallenge.java` 必须新增：

    public record ConfirmationChallenge(
        String challengeId,
        String proposalId,
        String conversationId,
        String prompt,            // "该组实际仅 2 张：确认按 2 张处理，还是漏传图片？"
        ConfirmationChallengeStatus status,
        Instant createdAt,
        Instant expiresAt
    ) {}

    public enum ConfirmationChallengeStatus {
        PENDING, CONFIRMED, EXPIRED
    }

`pixflow-module-dag/.../PendingPlanMapper.java` 必须新增：

    Optional<PendingPlan> findByProposalId(String proposalId);
    void updateStatus(String proposalId, PendingPlanStatus status);

`pixflow-module-imagegen/.../PendingImagegenPlanMapper.java` 必须新增：

    Optional<PendingImagegenPlan> findByProposalId(String proposalId);
    void updateStatus(String proposalId, PendingImagegenPlanStatus status);

`pixflow-permission/.../ConfirmationTokenService.java` 必须新增：

    ConfirmationToken issueForChallenge(ConfirmationChallenge challenge);
    Optional<ConfirmationToken> verifyChallengeToken(String challengeToken);

`pixflow-module-file/.../PackageReferenceResolver.java` 必须新增：

    PackageReference resolve(String packageId);
    List<ImageReference> listImages(String packageId);

### 错误码

`pixflow-common/.../error/ConversationErrorCode.java` 必须新增：

    CONVERSATION_NOT_FOUND          // 404
    CONVERSATION_ARCHIVED           // 410 Gone
    CONVERSATION_TITLE_INVALID      // 400
    PROPOSAL_NOT_FOUND              // 404
    PROPOSAL_ALREADY_CONFIRMED      // 409
    PROPOSAL_CHALLENGE_EXPIRED      // 410 Gone
    PROPOSAL_CHALLENGE_FAILED       // 400
    CONFIRMATION_TOKEN_INVALID      // 401
    CONFIRMATION_TOKEN_EXPIRED      // 401
    LOCK_ACQUISITION_FAILED         // 503
    ATTACHMENT_INVALID              // 400
    PACKAGE_REFERENCE_INVALID       // 404

### 配置项

`pixflow-conversation/.../config/ConversationProperties.java` 暴露：

    pixflow:
      conversation:
        sse:
          timeout: 5m                  # SSE 流超时
          heartbeat-interval: 30s      # 心跳间隔（防代理超时断开）
        lock:
          ttl: 60s                     # 会话级锁 TTL
          watchdog-interval: 10s       # 看门狗续期间隔
        confirmation:
          batch-threshold: 50          # 大批量二次确认阈值
          challenge-ttl: 5m            # challenge 有效期
          token-ttl: 10m               # 确认令牌有效期

## Revision Notes

- 2026-06-30 / Kiro: 新建 `conversation-module-implementation-plan.md` 作为 `module/conversation` 的实施蓝图。确立 7 个落地阶段：阶段 1 更新设计与依赖地基、阶段 2 conversation 表 CRUD、阶段 3 附件瘦版、阶段 4 会话级锁 + SSE 流式、阶段 5 确认 REST 边界 + 二次确认挑战、阶段 6 取消 / 进度入站、阶段 7 ArchUnit 守护 + 集成测试 + 正式模块设计文档。所有设计决策与会话级锁选型、确认端点形态、附件 V1 范围、ATTACHMENT 投影落点、素材包绑定语义、SSE 重连策略、历史查询视图、Spring MVC 同步、附件统一走素材包均固化于 Decision Log。
- 2026-06-30 / Codex: 同步第一版实现进度。标记 `pixflow-conversation` 模块骨架、conversation CRUD、session 只读历史接口、attachment 瘦版、PackageReferenceResolver、SSE sink、ConversationLock、HistoryQueryService、ConfirmationController/Service 骨架、CancellationService、ConversationProgressBridge、ConversationAutoConfiguration、app 依赖与最小测试护栏已完成；记录 `AgentTurnRunner` SPI、共用 `pending_plan` schema 与精选 Maven 验证结果；保留 `AttachmentContextPreparer`、真实 agent runner、完整 HITL schema、端到端集成测试等未完成项。
