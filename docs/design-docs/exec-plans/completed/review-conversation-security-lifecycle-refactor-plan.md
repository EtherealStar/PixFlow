# 按代码审查报告重构 Conversation 鉴权绑定、确认副作用、SSE 生命周期与附件边界

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文面向完全不了解当前会话的执行者：只要有当前工作树、这份计划和代码审查报告，就应能完成重构、验证结果，并知道为什么这样做。

## Purpose / Big Picture

这份计划要按代码审查报告彻底修复 `pixflow-conversation` 的高风险正确性和安全边界问题。完成后，登录用户只能读取和操作自己拥有的 conversation、proposal、task 和 package 绑定；确认提交流程不会重放 challenge、不会在 CAS 失败前创建不可逆 task；取消接口会先证明 task 属于当前会话和当前用户再取消；SSE 心跳、executor 提交、turn lock 和错误帧都有确定的清理路径；附件、metadata、分页和 schema 边界不再靠 JDK 的偶发 `NullPointerException` 或下游 `NumberFormatException` 暴露。

本计划不是给旧实现补一个兼容分支。凡是审查报告指出的旧路径本身就是风险来源的，都要重构删除或替换：删除仅凭 `conversationId` 查询历史的 service/controller 签名，删除未绑定 owner 的 conversation 表契约，删除“校验 conversationId 但按 taskId 取消”的路径，删除 challenge `get/check/delete` 非原子消费，删除先 `createAndEnqueue` 再 `markConfirmed` 的副作用顺序，删除 `DAG_PLAN_EXPIRED` 被包装为确认成功的语义，删除 `catch (Throwable)` 后继续尝试 SSE 序列化的路径，删除 metadata 的浅拷贝/null 透传，删除 untrimmed packageId 与 broad `catch (RuntimeException)` 误分类，删除无上限 page/offset 和弱 timestamp/index schema。项目仍处开发阶段，不为了迁移式安全保留旧代码、旧 API 签名或旧弱 schema。

## Progress

- [x] (2026-07-09 16:20+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md` 和当前 active exec plans，确认本计划必须放在 `docs/design-docs/exec-plans/`，且要避开已有 `agent` 与上传/loop active plan 的实现范围。
- [x] (2026-07-09 16:35+08:00) 阅读代码审查报告，归纳出对象归属绑定、确认二阶段竞态、SSE 生命周期、附件/metadata、错误语义、分页/schema 六组 conversation 风险。
- [x] (2026-07-09 16:50+08:00) 阅读 `docs/design-docs/module/conversation.md`、`infra/auth.md`、`infra/permission.md`、`harness/session.md`、`module/task.md`、`module/dag.md`、`module/file.md`，确认修复必须对齐 owner_user_id、确认令牌隔离、message 表唯一写者、task 外壳边界、pending_plan CAS、file 包引用契约。
- [x] (2026-07-09 17:10+08:00) 读取热点代码：`HistoryController`、`MessageController`、`CancellationController`、`ConfirmationController`、`SseAgentEventSink`、`SseHeartbeat`、`ConfirmationService`、`CancellationService`、`TurnDispatchService`、`HistoryQueryService`、`MessageSubmitRequest`、`Attachment`、`AttachmentCollector`、`AttachmentMapper`、`ConversationProperties`、conversation migration、`ConversationService` 和 task cancel API，确认报告中的风险仍在当前工作树。
- [x] (2026-07-09 17:30+08:00) 新建本中文 ExecPlan，明确重构机制、实施顺序、旧代码删除范围、测试矩阵和需要同步的设计文档。
- [x] (2026-07-09 13:42+08:00) 第一阶段实施：把 AuthPrincipal/owner_user_id 贯穿 conversation CRUD、history、message submit、confirm、cancel，并删除无 owner 的 service 方法。
- [x] (2026-07-09 13:42+08:00) 第二阶段实施：重构 confirmation 为“challenge 原子消费 → proposal CAS → task 创建/入队”的顺序，并把 taskId 写回 pending proposal 放进同一可恢复语义。
- [x] (2026-07-09 13:42+08:00) 第三阶段实施：重构 cancellation 的 task 归属验证、taskId 边界解析和失败响应语义。
- [x] (2026-07-09 13:42+08:00) 第四阶段实施：修复 SSE executor/heartbeat/Throwable/turn lock 生命周期。
- [x] (2026-07-09 13:42+08:00) 第五阶段实施：统一 attachment metadata、分页和 schema 配置边界；request 顶层 metadata 当前仍未被业务消费，未另做投影。
- [ ] 第六阶段实施：更新相关设计文档并运行完整验证。

## Surprises & Discoveries

- Observation: `pixflow-conversation` 没有依赖 `pixflow-infra-auth`，controller 接入 `@CurrentUser AuthPrincipal` 后必须显式补 Maven 依赖。
  Evidence: 首次 `mvn -pl pixflow-conversation,pixflow-module-task,pixflow-module-dag,pixflow-infra-cache -am -DskipTests compile` 在 conversation 编译阶段报 `com.pixflow.infra.auth.context` 包不存在；补 `pixflow-infra-auth` 依赖后主源码编译通过。

- Observation: 完整 reactor 测试当前被 `pixflow-common` 的既有测试阻断，未执行到 conversation 模块。
  Evidence: `mvn -pl pixflow-conversation,pixflow-module-task,pixflow-module-dag,pixflow-infra-cache -am test` 在 `ErrorNormalizerTest.normalizesMethodNotSupportedTo405Code` 报 `HttpRequestMethodNotSupportedException(String, String[]) is not visible`；单独运行 `mvn -pl pixflow-conversation -Dtest=ConversationServiceTest test` 通过。

- Observation: `docs/design-docs/index.md` 把根级 `docs/design-docs/api.md` 列为后端 API 文档，但当前工作树没有该文件；实际存在的是 `docs/design-docs/frontend/api.md`。
  Evidence: `Get-Content docs\design-docs\api.md` 返回路径不存在；`rg --files docs\design-docs` 只列出 `frontend/api.md`。

- Observation: `infra/auth.md` 已明确顶层资源必须带 `owner_user_id`，且 `pixflow-infra-auth` 已提供 `AuthPrincipal`、`@CurrentUser` 和 `AuthContextHolder`，但 `conversation` 表和 `ConversationService` 仍没有 owner 字段或过滤。
  Evidence: `ConversationEntity` 只有 `id/title/packageId/archived/createdAt/updatedAt`；`V1__create_conversation_tables.sql` 没有 `owner_user_id`；controller 方法没有 `@CurrentUser AuthPrincipal` 参数。

- Observation: `HistoryController.timeline` 只传 caller-supplied `conversationId` 给 `HistoryQueryService.timeline`，后者只 `conversationService.requireActive(conversationId)` 后按 conversationId 查询 `message` 表。
  Evidence: `HistoryController.java` 和 `HistoryQueryService.java` 当前签名均没有 owner/principal 参数。

- Observation: `CancellationService.cancel` 只验证 conversation active，然后构造 `CancelTaskCommand(new TaskId(taskId), "user_cancelled", conversationId)`；task 模块内部直接 `Long.parseLong(command.taskId().value())` 查任务并取消。
  Evidence: `pixflow-module-task/internal/cancel/CancellationService` 对 `process_task` 只按 id 查询，没有 conversation/user 约束；非法 taskId 会抛 raw `NumberFormatException`。

- Observation: `ConfirmationService.verifyChallenge` 按 challengeId 读取 Redis 后只校验答案，不校验 challenge 中的 `proposalId` 与 `conversationId` 是否等于当前提交对象，且 `get` / `delete` 不是原子操作。
  Evidence: `verifyChallenge(ConfirmationSubmitRequest)` 没有接收 `conversationId` 或 `proposalId`；成功后调用 `cacheStore.delete(key)`，并发窗口内两个 submit 可同时通过 get/check。

- Observation: `ConfirmationService.submit` 在 `proposalRepository.markConfirmed` CAS 之前调用 `taskCommandService.createAndEnqueue`。如果 CAS 因竞争、过期或状态改变失败，task 已经创建或入队。
  Evidence: 当前顺序是 `issueToken` → `verifyAndConsume` → `createTaskIfPossible` → `proposalRepository.markConfirmed`；catch 中把 `DAG_PLAN_EXPIRED` 也转成 `"CONFIRMED"` 响应。

- Observation: `MessageController.submit` 在 `heartbeat.start()` 之后调用 `conversationExecutor.execute`，没有捕获 `RejectedExecutionException` 或 executor 关闭错误；如果提交失败，heartbeat 已启动且 emitter 不会完成。
  Evidence: `conversationExecutor.execute(() -> {...})` 外层没有 try/catch/finally。

- Observation: `MessageController` 在 worker 线程里 `catch (Throwable ex)`，会吞掉 `OutOfMemoryError`、`StackOverflowError` 等 fatal error，并尝试继续通过 SSE 序列化错误。
  Evidence: catch 类型为 `Throwable`，随后调用 `sink.error(ex)`。

- Observation: `SseHeartbeat.stop()` 可在 `future` 赋值前运行。`start()` 后续仍可能把 `scheduleAtFixedRate` 的 future 赋给字段，导致停止后的 heartbeat 任务存在。
  Evidence: `start()` 先注册 emitter callbacks，再调 `scheduler.scheduleAtFixedRate` 并赋值；`stop()` 只取消当时已可见的 `future`。

- Observation: `TurnDispatchService.dispatch` 在收集附件后才取 turn lock；昂贵 package 展开会在忙碌会话上白做。更严重的是，一旦取锁后 runner 解析前出现 unchecked exception，handle 没返回给 controller，锁可能泄漏。
  Evidence: 当前顺序是 `requireActive` → `collectAttachments` → `conversationLock.tryLock` → `resolve runner` → 返回 handle。

- Observation: `AttachmentCollector.fromPackage` 把 `image.skuId()`、`groupKey()`、`viewId()` 等 nullable 值放入 `LinkedHashMap` 后传给 `Attachment`，而 `Attachment` 使用 `Map.copyOf`，会因为 null value 抛 raw NPE；同时 broad `catch (RuntimeException)` 会把 resolver bug、DB 故障和上述 NPE 全部映射成 `PACKAGE_REFERENCE_INVALID` 且丢 cause。
  Evidence: `metadata.put("skuId", image.skuId())` 等未过滤 null；`Attachment` compact constructor 调 `Map.copyOf(metadata)`；`fromPackage` catch `RuntimeException` 后新建 `BusinessException` 不带 cause。

- Observation: `AttachmentMapper.toLoopAttachments` 无论 `AttachmentType` 是 `UPLOAD_IMAGE` 还是 `PACKAGE_REFERENCE` 都投影成 loop attachment kind `"image"`，丢失领域种类。
  Evidence: `new com.pixflow.harness.loop.Attachment(attachment.attachmentId(), "image", ...)`。

- Observation: `HistoryQueryService` 只 clamp request-provided size，不 clamp configured default page size；`page` 没有上限，`offset = (page - 1) * size` 可 long overflow。
  Evidence: `resolvedSize = size == null ? properties.getHistory().getDefaultPageSize() : ...`；`resolvedPage = Math.max(1L, page)` 后直接乘法。

## Decision Log

- Decision: 所有用户可见的 conversation 入口统一以 `AuthPrincipal.userId` 为第一等参数，conversation 表新增 `owner_user_id` 并对读写查询强制 owner 过滤。
  Rationale: `infra/auth.md` 已规定顶层资源必须带 owner。只在 controller 做登录校验但 service 仍按裸 `conversationId` 查询，无法阻止横向访问。owner 过滤必须进入 service/repository 层，测试和内部调用也必须显式提供 owner。
  Date/Author: 2026-07-09 / Codex

- Decision: `ConversationService` 删除无 owner 的 `create/list/detail/requireActive/archive` 公共方法，替换为 owner-aware API；controller 全部接入 `@CurrentUser AuthPrincipal`。
  Rationale: 保留旧无 owner 方法会让后续调用点继续绕过归属校验。开发阶段应直接改签名并修正编译错误，而不是保留兼容重载。
  Date/Author: 2026-07-09 / Codex

- Decision: 确认提交的不可逆副作用必须发生在 proposal CAS 成功之后；task 创建与入队不能早于 `PENDING → CONFIRMING/CONFIRMED` 的受控状态迁移。
  Rationale: task 是真实执行外壳，创建/入队后会被 worker 消费。旧顺序在 CAS 失败、过期或并发竞争时留下无法解释的 task。正确机制是先占有 proposal 的确认权，再创建 task，再把 taskId 绑定回 proposal；任一步失败都有明确恢复语义。
  Date/Author: 2026-07-09 / Codex

- Decision: challenge 消费必须是“原子 get-and-delete + proposal/conversation 绑定校验”，不能继续使用 `get` / `check` / `delete` 三步。
  Rationale: challenge 是一次性二次确认凭据，必须绑定当前 proposal 和 conversation，否则可被重放到另一个待确认提案；非原子删除在并发 submit 下可复用同一个 challenge。
  Date/Author: 2026-07-09 / Codex

- Decision: 取消任务时，conversation 侧先把 taskId 解析为 `long` 并验证 task 的 `conversation_id` 和 owner，再调用 task cancel；task API 同步收紧为 typed identity。
  Rationale: 取消不是“知道 taskId 就能取消”。`process_task` 已有 `conversation_id`，可用于对象绑定；owner 要通过 owning conversation 建立。非法 taskId 属于 validation，应在 web/service 边界变为受控错误，而不是落到 task 内部 raw `NumberFormatException`。
  Date/Author: 2026-07-09 / Codex

- Decision: SSE 只捕获 `Exception`/业务异常并归一化，不捕获 fatal `Throwable`；executor 提交失败必须立即停止 heartbeat 并完成 emitter。
  Rationale: fatal JVM error 不是可恢复业务错误，吞掉会掩盖进程级故障。executor 拒绝时 worker 根本不会运行，必须由提交线程负责清理 heartbeat/emitter。
  Date/Author: 2026-07-09 / Codex

- Decision: turn lock 在尽可能早的轻量校验后获取，锁内做附件展开；锁获取后任意后续失败都由 `try/finally` 释放。
  Rationale: 同会话忙时不应先展开 package。锁是跨节点状态，必须在所有 post-lock 初始化失败路径释放，不能依赖 controller 拿到 handle 后再 close。
  Date/Author: 2026-07-09 / Codex

- Decision: metadata 统一归一化为 JSON-safe map：拒绝 null key，删除或拒绝 null value，集合深拷贝，复杂对象在边界失败。
  Rationale: conversation 是 web 边界，不应把用户/上游传入的可变 map、null 和复杂对象透传给 `Map.copyOf`、`Map.of`、ObjectMapper 或 loop metadata。统一边界比在每个投影点兜底更可测。
  Date/Author: 2026-07-09 / Codex

- Decision: conversation migration 在开发阶段改为强 schema，不保留弱 timestamp/index 口径。
  Rationale: 项目仍处开发阶段，审查报告要求可重构修复。conversation 表应直接增加 owner/default timestamp/update timestamp 和查询索引；如果本地 dev DB 形状漂移，应按开发流程重建，而不是继续保留弱约束。
  Date/Author: 2026-07-09 / Codex

## Outcomes & Retrospective

本文件已开始实施代码。当前已落地 owner 绑定、确认副作用重排、取消归属校验、SSE/锁生命周期、附件 metadata/pagination/schema 配置收紧和局部测试同步。

当前已观察到：

    mvn -pl pixflow-conversation,pixflow-module-task,pixflow-module-dag,pixflow-infra-cache -am -DskipTests compile
    mvn -pl pixflow-conversation,pixflow-module-task,pixflow-module-dag,pixflow-infra-cache -am test-compile -DskipTests
    mvn -pl pixflow-conversation -Dtest=ConversationServiceTest test

均通过。

完整 reactor 测试当前被 `pixflow-common` 既有测试阻断，阻断点与本计划修改无关。待该公共测试修复后，仍需继续执行原计划的完整验证：

    mvn -pl pixflow-conversation -am test
    mvn -pl pixflow-conversation,pixflow-module-task,pixflow-app -am -DskipTests compile

均通过；同时旧风险字符串检查不再命中生产 Java / SQL 路径。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。本计划主要修改 `pixflow-conversation`，但必须推动 `pixflow-module-task` 的公开 cancel 契约和相关设计文档同步，因为取消安全需要 task 归属事实源配合。

`pixflow-conversation` 是 web 边界层，拥有 conversation 表 CRUD、用户消息入站、SSE 输出、确认 REST、取消、附件收集和历史查询。它不拥有 `message` 表写入，不组装 prompt，不执行 task worker。`harness/session` 是 `message` 表唯一写者；conversation 只能用 `MessageReadMapper` 做历史展示读。

`pixflow-infra-auth` 已提供 `AuthPrincipal`、`@CurrentUser` 和 `AuthContextHolder`。`infra/auth.md` 明确规定 `conversation` 等顶层资源必须有 `owner_user_id` 并在查询时按当前用户过滤。当前 conversation 代码尚未消费这些原语，这是历史查询和动作授权漏洞的根因。

`pixflow-module-task` 的 `process_task` 表已有 `conversation_id` 字段，`ProcessTask` 也有 `conversationId` 属性。当前 cancel API 只接 `TaskId/reason/requestedBy`，task 内部按 id 查询并取消。conversation 可以通过扩展 task API 或新增 task ownership query 来证明 `taskId` 属于当前 conversation；owner 由 conversation 表的 owner 过滤间接保证。

`ConfirmationService` 是本计划最高风险文件。它同时处理 challenge、confirmation token、permission verify、task 创建和 pending proposal CAS。旧实现把 task 创建放在 CAS 之前，导致确认失败也可能创建任务；challenge 只按 challengeId 验证，没有绑定 proposal/conversation；`DAG_PLAN_EXPIRED` 被当成幂等确认成功返回，掩盖真实失败。

这里的“对象绑定”指对每个外部传入 id 都证明它属于当前可信主体和当前操作上下文。例如历史查询要证明 `conversationId.owner_user_id == currentUser.userId`；取消要证明 `taskId.conversation_id == conversationId` 且 `conversationId.owner_user_id == currentUser.userId`；确认要证明 `proposalId.conversation_id == conversationId`，challenge 也绑定同一个 proposal/conversation。

这里的“原子消费”指一次性凭据的读取和删除必须在存储层成为一个不可分割操作。Redis 实现可以是 Lua `GET + DEL`，也可以由 `CacheStore` 暴露 `getAndDelete` / `consume` 方法。Java 层先 `get` 再 `delete` 不是原子消费。

## Plan of Work

第一阶段重构 conversation owner 绑定。修改 `pixflow-conversation/src/main/resources/db/migration/V1__create_conversation_tables.sql`，给 `conversation` 增加 `owner_user_id BIGINT NOT NULL`，`created_at` / `updated_at` 加 `DEFAULT CURRENT_TIMESTAMP(6)`，`updated_at` 加 `ON UPDATE CURRENT_TIMESTAMP(6)` 或由应用明确管理但 schema 默认不再缺失。索引改为覆盖 owner 查询：`idx_conversation_owner_archived_updated(owner_user_id, archived, updated_at DESC)` 和 `idx_conversation_owner_updated(owner_user_id, updated_at DESC)`，后者服务 `includeArchived=true` 的全局 updated 排序。更新 `ConversationEntity` 增加 `ownerUserId` 字段。更新 `ConversationView` 如前端不需要 owner 可以不返回，但 service 必须持有。

第二阶段删除无 owner 的 conversation service API。把 `ConversationService.create(CreateConversationRequest)` 改为 `create(AuthPrincipal principal, CreateConversationRequest request)` 或 `create(long ownerUserId, ...)`；`list/detail/requireActive/archive` 全部增加 owner 参数。`requireActive(ownerUserId, conversationId)` 内部用 `selectOne where id=? and owner_user_id=?`，查不到统一返回 `CONVERSATION_NOT_FOUND`，不要暴露“存在但不属于你”。`ConversationController`、`HistoryController`、`MessageController`、`ConfirmationController`、`CancellationController` 全部接入 `@CurrentUser AuthPrincipal principal` 并传 `principal.userId()`。删除旧无 owner 方法，让编译器暴露所有遗漏调用点。

第三阶段把 history 的 owner 绑定落到服务入口。`HistoryQueryService.timeline` 签名改为 `timeline(long ownerUserId, String conversationId, Long page, Long size)`。先调用 `conversationService.requireActive(ownerUserId, conversationId)`，再用 conversationId 查询 message 表。message 表不需要复制 owner，因为 conversation 表是 owner 事实源；但查询前必须已经完成 owner proof。新增双用户测试：同一个裸 conversationId 只有 owner 能查历史，其他用户得到 `CONVERSATION_NOT_FOUND`。

第四阶段重构 message submit 和 turn dispatch 生命周期。`MessageController.submit` 传 ownerUserId 给 `TurnDispatchService.dispatch`。`TurnDispatchService` 先做轻量的 `conversationService.requireActive(ownerUserId, conversationId)`，再获取 turn lock，锁内执行附件收集、runner resolve 和 runner function 构造。若锁后任意步骤失败，立即关闭 lock handle 再抛出，不能让锁泄漏。把昂贵 package 展开移到锁内是有意取舍：忙会话先快速失败；拿到锁后，附件展开属于本回合工作，失败则释放锁并通过 SSE/HTTP 返回错误。

第五阶段重构 confirmation challenge。新增 `ConfirmationChallengeStore` 或扩展 `CacheStore`，提供 `consumeChallenge(challengeId)` 的原子 get-and-delete。Redis 实现使用 Lua 或 cache 原语保证并发下只有一个 submit 得到 challenge。`verifyChallenge` 改为接收 `conversationId`、`proposalId`、request，并校验 stored challenge 的 `conversationId`、`proposalId`、`status=PENDING`、`expiresAt` 和 answer。challengeId 不存在或已消费统一为 `PROPOSAL_CHALLENGE_EXPIRED` 或更准确的 `PROPOSAL_CHALLENGE_FAILED`，但不得落到 raw cache 异常。

第六阶段重构 confirmation 副作用顺序。不要再执行 `createTaskIfPossible` 后才 `markConfirmed`。推荐机制是给 `PendingProposalRepository` / dag 的 `PendingPlanService` 增加两阶段 CAS：

    PENDING -> CONFIRMING
    CONFIRMING -> CONFIRMED(taskId)
    CONFIRMING -> PENDING or FAILED_CONFIRMATION on task create failure

`ConfirmationService.submit` 的顺序应为：owner active proof → require proposal belongs to conversation and status PENDING/CONFIRMED → high threshold challenge 原子消费 → issue token → verifyAndConsume token → `proposalRepository.startConfirmation(proposalId, expectedVersion)` CAS 占有确认权 → 创建并入队 task → `proposalRepository.markConfirmedWithTask(startedProposal, taskId)`。如果 CAS 返回 already confirmed，则重读并返回既有 taskId。若 CAS 返回 expired/rejected，则返回明确失败，不创建 task，不伪装 `CONFIRMED`。

如果不想新增 `CONFIRMING` 状态，也可以把 `PendingPlanService.confirm` 改成接收一个 `Supplier<TaskId>` 并在同一 service 方法内先 CAS 锁定行再创建 task 再更新 taskId；但不要把外部 task 创建放在 CAS 之前。因为 task 创建与 pending_plan 更新不在同一数据库事务，`CONFIRMING` 状态更可恢复：恢复扫描可以找到卡在 `CONFIRMING` 且有 idempotency key 的 proposal，按 task idempotency 继续或回滚。

第七阶段修正 confirmation 成功语义。`DAG_PLAN_EXPIRED`、`REJECTED`、`FAILED_CONFIRMATION` 等非 confirmed 状态必须返回相应错误或状态，不得被 catch 后包装成 `"CONFIRMED"`。只有重读后 `latest.status() == CONFIRMED` 且 `latest.taskId()` 非空，才允许作为幂等成功返回。低阈值路径仍不需要 challenge，但同样走 token issue/verify 和 proposal CAS。`parsePackageId` 不能再吞掉非法 packageId 返回 0；如果 proposal 类型必须有 packageId，则非法 packageId 是 proposal 数据损坏或 validation error，应失败而不是跳过创建 task 后把 proposal 标确认。

第八阶段重构 cancellation 安全边界。`CancellationController.cancel` 接 `@CurrentUser AuthPrincipal`，先把 `taskId` path variable 解析为 long。新增 `ConversationErrorCode.TASK_ID_INVALID`、`TASK_NOT_FOUND`、`TASK_NOT_CANCELABLE` 或复用 task 错误码，但 HTTP 语义要区分 400/404/409。conversation 侧调用新的 task API：

    TaskCancellationTarget target = taskQuery.requireCancellationTarget(taskId);
    assert target.conversationId().equals(conversationId)

或者把 `CancelTaskCommand` 改为包含 `long taskId, String conversationId, long requesterUserId`，由 task 内部 SQL `where id=? and conversation_id=?` 绑定。因为 owner 在 conversation 已证明，task 不一定要知道 owner_user_id；如果后续 task 表也加 owner_user_id，则 task SQL 同时绑定 owner 更好。旧 `CancelTaskCommand(TaskId taskId, String reason, String requestedBy)` 删除，不保留 `requestedBy=conversationId` 这种误导字段。

第九阶段修正 cancellation API 响应语义。`CancellationController` 不应总是 `ApiResponse.ok`。若 task 不存在或不属于当前 conversation，返回 404；若 task 已 terminal，返回 409 或 `CancellationResult(cancelled=false, reason=terminal)` 且 API envelope 不标业务成功。服务层要让 controller/advice 能映射 HTTP status。新增测试证明不存在、跨 conversation、terminal、queued、running 五种场景。

第十阶段修复 `MessageController` SSE 清理。把 `heartbeat.start()` 与 `conversationExecutor.execute(...)` 包在 try/catch 中。若 `execute` 抛 `RuntimeException`，立即 `heartbeat.stop()`，发送受控 error frame 或 `emitter.completeWithError`，并返回已完成 emitter。worker 线程内 catch 改为 `catch (Exception ex)` 或捕获 `PixFlowException` + `RuntimeException`，不要捕获 `Throwable`。错误归一化后只发送 safe message/errorCode/traceId，不把 raw `ex.getMessage()` 直接暴露给前端。`finally` 里停止 heartbeat；emitter completion callback 也停止 heartbeat。

第十一阶段修复 `SseHeartbeat` race。把 `future` 改为 `AtomicReference<ScheduledFuture<?>>`，`start()` 在 schedule 后如果发现 `stopped=true`，立即 cancel 新 future；`stop()` 用 `getAndSet(null)` 取消。更严格的实现是 `start()` 先 `if (stopped.get()) return`，schedule 后 CAS 设置 future，失败或 stopped 则 cancel。测试用 fake scheduler 或手动 callback 复现 stop-before-assignment，不应留下活跃 fixed-rate 任务。

第十二阶段修复 `SseAgentEventSink` null/metadata 投影。`toPayload` 不再把 payload metadata 原样放入 `Map.of`。新增 `SsePayloads.jsonSafeMap(Object value)`，递归删除或拒绝 null key/null value，并只保留 String/Number/Boolean/List/Map/Enum 等 JSON-safe 值。`error(Throwable)` 输出结构化错误：`errorCode`、`message`、`traceId` 可选；message 是 safe message，不直接用 raw exception message。`emit` 遇到 IOException 抛 `SseClientDisconnected` 或受控 exception，让 controller 以普通 client disconnect 收敛。

第十三阶段重构 request 和 attachment 边界。`MessageSubmitRequest` compact constructor 防御性复制 `attachments` 和 `metadata`，metadata 走统一 JSON-safe normalizer，attachments 列表元素非 null。`Attachment` 同样使用深拷贝 JSON-safe metadata，不再裸 `Map.copyOf`。`AttachmentCollector.fromInput` 对 sourceRef、type、packageId 做边界校验，所有 validation 失败抛 `BusinessException(ATTACHMENT_INVALID)`。`fromPackage` 先 trim packageId；只捕获 `PackageReferenceNotFound`、`BusinessException` 或 resolver 声明的受控异常并保留 cause，DB/bug/runtime 失败不要误报为用户 packageId 错误。

第十四阶段修复 package attachment 映射。`AttachmentMapper.toLoopAttachments` 的 kind 不再固定 `"image"`。可选机制一：把 loop `Attachment.kind` 设置为 `upload_image` / `package_reference`；可选机制二：如果 loop 只能识别 image，则 `kind="image"` 保持物理媒体类型，但 metadata 必须包含 `attachmentType=UPLOAD_IMAGE/PACKAGE_REFERENCE`、`packageId`、`imageId` 等字段。无论选哪种，不能丢失 package reference 与 uploaded image 的区别。相关设计应同步 `module/conversation.md` 和 loop attachment 契约。

第十五阶段修复 history pagination 和配置校验。`ConversationProperties.History` 启动期校验 `1 <= defaultPageSize <= maxPageSize <= HARD_MAX`。`HistoryQueryService` 对 default 与 request size 使用同一个 resolver。`page` 增加上限，或使用 `Math.multiplyExact` 捕获 overflow 后抛 `VALIDATION`。`offset` 必须非负且不超过 mapper/DB 可接受上限。新增 `PAGE_INVALID` 或用通用 validation 错误码。`ConversationProperties` 其他 Duration/list 也做基本校验：SSE timeout > 0、heartbeat >= 0、confirmation ttl > 0、batchThreshold >= 1、permitLiteralAnswers 非空且 trim 后非空。

第十六阶段修复 schema 与索引。conversation migration 改为强 schema：`owner_user_id` 非空、timestamp 默认、owner 维度索引。`includeArchived=true` 的 list 查询按 `owner_user_id, updated_at DESC` 使用索引；`includeArchived=false` 用 `owner_user_id, archived, updated_at DESC`。如果 MyBatis-Plus 的字段映射需要下划线配置，确认 `ownerUserId` 可映射 `owner_user_id`。因为项目处开发阶段，直接更新 V1，不追加保留旧形状的兼容迁移；执行者本地 dev DB 需要重建时按项目开发流程处理。

第十七阶段补齐测试和架构守护。新增或更新 controller/service 单测、SSE race 测试、confirmation 并发测试、task cancellation binding 测试、attachment metadata 测试、history pagination 测试和 schema/migration 测试。ArchUnit 增加规则：conversation controller 暴露用户资源的方法必须接 `AuthPrincipal` 或等价 owner 参数；conversation service 不应有 public `requireActive(String)`、`detail(String)` 等无 owner 方法；conversation 不直接写 `message` 表；confirmation submit 不允许在 `markConfirmed/startConfirmation` 之前调用 `createAndEnqueue`。

第十八阶段同步设计文档。更新 `docs/design-docs/module/conversation.md` Revision Notes，记录 owner_user_id、owner-aware service、challenge 原子消费、confirmation 两阶段 CAS、cancellation task binding、SSE cleanup 和 metadata normalizer。更新 `docs/design-docs/infra/auth.md` 的“module/conversation owner 过滤”实现状态。更新 `docs/design-docs/module/task.md` 的 cancel API 契约，明确 cancel 必须按 taskId + conversationId 绑定，非法/terminal task 的语义。更新 `docs/design-docs/frontend/api.md` 的 conversation/cancel/confirm 错误响应；若恢复根级 `docs/design-docs/api.md`，同时修正 `index.md` 对 API 文档的指向。

## Concrete Steps

所有命令从仓库根目录 `D:\study\PixFlow` 执行。

先确认旧风险路径：

    rg -n "requireActive\\(String conversationId\\)|timeline\\(String conversationId|catch \\(Throwable|createTaskIfPossible\\(proposal\\).*markConfirmed|get\\(key, ConfirmationChallenge|cacheStore\\.delete\\(key\\)|new TaskId\\(taskId\\)|Long\\.parseLong\\(command\\.taskId|Map\\.copyOf\\(metadata\\)|metadata\\.put\\(\"skuId\"|\"image\"\\s*,\\s*attachment\\.sourceRef\\(\\)" pixflow-conversation/src/main/java pixflow-module-task/src/main/java

实施完成后，同一命令不应在生产 Java 里命中旧风险路径。允许命中本计划或测试中的“旧行为应不存在”断言，不允许命中生产实现。

第一组改 owner schema 和服务签名：

    pixflow-conversation/src/main/resources/db/migration/V1__create_conversation_tables.sql
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/persistence/ConversationEntity.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/app/ConversationService.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/ConversationController.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/HistoryController.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/MessageController.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/ConfirmationController.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/CancellationController.java

新增测试：

    pixflow-conversation/src/test/java/com/pixflow/module/conversation/app/ConversationOwnershipTest.java
    pixflow-conversation/src/test/java/com/pixflow/module/conversation/app/HistoryQueryOwnershipTest.java

第二组改 confirmation：

    pixflow-conversation/src/main/java/com/pixflow/module/conversation/app/ConfirmationService.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/proposal/PendingProposalRepository.java
    pixflow-module-dag/src/main/java/com/pixflow/module/dag/propose/PendingPlanService.java
    pixflow-module-dag/src/main/java/com/pixflow/module/dag/propose/PendingPlanStatus.java

新增测试：

    pixflow-conversation/src/test/java/com/pixflow/module/conversation/app/ConfirmationServiceChallengeReplayTest.java
    pixflow-conversation/src/test/java/com/pixflow/module/conversation/app/ConfirmationServiceTaskOrderingTest.java
    pixflow-conversation/src/test/java/com/pixflow/module/conversation/app/ConfirmationServiceConcurrencyTest.java

第三组改 cancellation：

    pixflow-conversation/src/main/java/com/pixflow/module/conversation/app/CancellationService.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/CancellationController.java
    pixflow-module-task/src/main/java/com/pixflow/module/task/api/TaskCommandService.java
    pixflow-module-task/src/main/java/com/pixflow/module/task/api/command/CancelTaskCommand.java
    pixflow-module-task/src/main/java/com/pixflow/module/task/internal/cancel/CancellationService.java
    pixflow-module-task/src/main/java/com/pixflow/module/task/infra/persistence/ProcessTaskMapper.java

新增测试：

    pixflow-conversation/src/test/java/com/pixflow/module/conversation/app/CancellationServiceOwnershipTest.java
    pixflow-module-task/src/test/java/com/pixflow/module/task/internal/cancel/CancellationServiceBindingTest.java

第四组改 SSE / lock：

    pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/MessageController.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/SseAgentEventSink.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/SseHeartbeat.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/app/TurnDispatchService.java

新增测试：

    pixflow-conversation/src/test/java/com/pixflow/module/conversation/api/MessageControllerLifecycleTest.java
    pixflow-conversation/src/test/java/com/pixflow/module/conversation/api/SseHeartbeatRaceTest.java
    pixflow-conversation/src/test/java/com/pixflow/module/conversation/app/TurnDispatchServiceLockCleanupTest.java

第五组改附件、metadata、分页、配置：

    pixflow-conversation/src/main/java/com/pixflow/module/conversation/app/MessageSubmitRequest.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/attachment/Attachment.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/attachment/AttachmentCollector.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/attachment/AttachmentMapper.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/app/HistoryQueryService.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/config/ConversationProperties.java

新增测试：

    pixflow-conversation/src/test/java/com/pixflow/module/conversation/attachment/AttachmentMetadataContractTest.java
    pixflow-conversation/src/test/java/com/pixflow/module/conversation/attachment/AttachmentMapperKindTest.java
    pixflow-conversation/src/test/java/com/pixflow/module/conversation/app/HistoryPaginationBoundsTest.java
    pixflow-conversation/src/test/java/com/pixflow/module/conversation/config/ConversationPropertiesValidationTest.java

运行模块验证：

    mvn -pl pixflow-conversation -am test
    mvn -pl pixflow-conversation,pixflow-module-task -am test
    mvn -pl pixflow-app -am -DskipTests compile

如 task/dag API 被调整，还要运行：

    mvn -pl pixflow-module-dag,pixflow-module-task,pixflow-conversation -am test

收尾检查：

    rg -n "requireActive\\(String conversationId\\)|detail\\(String conversationId\\)|timeline\\(String conversationId|catch \\(Throwable|createTaskIfPossible\\(proposal\\).*markConfirmed|get\\(key, ConfirmationChallenge|cacheStore\\.delete\\(key\\)|new TaskId\\(taskId\\)|Long\\.parseLong\\(command\\.taskId|Map\\.copyOf\\(metadata\\)|metadata\\.put\\(\"skuId\"|return new ConfirmationSubmitResponse\\(latest\\.proposalId\\(\\), latest\\.taskId\\(\\), \"CONFIRMED\"\\)" pixflow-conversation/src/main/java pixflow-module-task/src/main/java
    rg -n "owner_user_id" pixflow-conversation/src/main/resources pixflow-conversation/src/main/java
    rg -n "CREATE TABLE conversation" pixflow-conversation/src/main/resources/db/migration/V1__create_conversation_tables.sql

第一条不应命中旧风险路径；第二条应命中 schema/entity/service owner 过滤；第三条人工检查 schema 包含 owner、timestamp defaults 和两个 owner 维度索引。

## Validation and Acceptance

Owner 绑定验收：创建两个用户 Alice 和 Bob。Alice 创建 conversation A。Bob 请求 `GET /api/conversations/A`、`GET /api/conversations/A/messages`、`POST /api/conversations/A/messages`、`POST /api/conversations/A/confirm/{proposalId}/submit`、`POST /api/conversations/A/tasks/{taskId}/cancel` 都应得到 not found 或 forbidden 的受控响应，不能看到 A 的数据，不能产生 task、cancel flag 或 SSE 回合。Alice 对同样接口正常工作。

History 验收：`HistoryQueryService.timeline(ownerUserId, conversationId, page, size)` 在 owner 正确时返回 message 分页；owner 错误时在 message 查询前失败。用 fake `MessageReadMapper` 断言 owner 错误时 mapper 不被调用。

Confirmation challenge 验收：proposal P1 和 P2 同属或不同属 conversation，P1 的 challengeId 不能用于 P2。两个并发 submit 使用同一 challengeId 时，只有一个能通过原子 consume；另一个得到 challenge expired/failed，且不会创建第二个 task。

Confirmation ordering 验收：注入 `proposalRepository.startConfirmation` CAS 失败时，`TaskCommandService.createAndEnqueue` 不被调用。注入 `createAndEnqueue` 失败时，proposal 不会被标为 CONFIRMED；若使用 CONFIRMING 状态，恢复逻辑能把它重试或标失败。注入 `markConfirmedWithTask` 失败时，idempotency key `proposal:{proposalId}` 可让重试找到同一 task，而不是创建第二个 task。

Expired proposal 验收：`DAG_PLAN_EXPIRED` 或 `proposal.status=EXPIRED` 返回明确过期错误，不返回 `"CONFIRMED"`。只有 latest proposal 状态确实是 `CONFIRMED` 且有 taskId 时才作为幂等成功。

Cancellation 验收：`taskId="abc"` 返回 validation error，不抛 `NumberFormatException`。task 不存在返回 404。task 属于另一个 conversation 返回 404。terminal task 返回 409 或受控 `cancelled=false` 业务状态。queued/running 且属于当前 conversation 的 task 才调用 task cancel，并设置 cancel flag。

SSE executor 验收：当 conversation executor 拒绝提交时，heartbeat 被停止，emitter 被完成或 completeWithError，turn lock 没有获取或已释放。worker 内普通业务异常发送一次 error frame 并停止 heartbeat。fatal `Error` 不被 `catch (Exception)` 捕获。

SseHeartbeat 验收：构造 stop-before-future-assignment 场景后，没有任何 fixed-rate task 留存。重复 stop 是幂等的。emitter completion、timeout、error callback 都能停止 heartbeat。

Turn lock 验收：附件展开抛 validation error、package resolver 抛受控 not found、runner resolve 抛 dependency error 时，如果锁已获取必须释放；如果锁未获取不做 package 展开。忙会话请求不触发 package resolver。

Attachment 验收：package image 的 nullable `skuId/groupKey/viewId` 不会触发 raw NPE；metadata 中 null value 要么被删除，要么被拒绝为 `ATTACHMENT_INVALID`，行为必须一致且测试固定。resolver 的 DB failure 不被误报为 package id invalid，日志保留 cause。

Pagination 验收：配置 `defaultPageSize=0` 或 `defaultPageSize>maxPageSize` 启动失败。请求 `page=Long.MAX_VALUE` 不产生负 offset 或 SQL overflow，而是 validation error。正常 page/size 被 clamp 到预期。

Schema 验收：空 dev DB 跑 Flyway 后，`conversation` 表有 `owner_user_id`、timestamp defaults/update 语义和 owner 维度索引。`includeArchived=true` 和 `false` 的 list 查询都有可用索引。旧无 owner 插入路径编译期消失。

## Idempotence and Recovery

本计划可以分阶段实施，但每阶段完成后必须运行对应测试。不要为了让旧测试继续通过而保留无 owner service 重载、旧 cancel command 或旧 confirmation 顺序；应更新测试替身，使它们表达新的安全契约。

owner schema 修改发生在开发阶段。若本地已有旧 dev DB，需要按项目开发流程重建或清理 Flyway 历史；不要把 `owner_user_id` 做成 nullable 以兼容旧行，也不要在 service 中给缺 owner 的旧 conversation 放行。生产迁移如果将来需要另行设计数据回填，不在本开发期计划内。

confirmation 的 task 创建与 proposal 更新不在同一事务，因此必须依靠 idempotency key 和中间状态恢复。推荐使用 `proposal:{proposalId}` 作为 task idempotency key；重试同一 proposal 时返回同一 taskId。若 task 创建成功但 mark confirmed 失败，下次 submit 应重读 task by idempotency key 后补写 proposal，而不是创建第二个 task。

challenge consume 必须可重复调用且失败安全：已经消费或过期时返回 empty/failed，不抛 raw Redis 异常；Redis 不可用时 submit 失败，不降级放行高阈值确认。

SSE 清理必须幂等：heartbeat.stop、emitter.complete、lock.close 都允许重复调用或在异常路径被调用。client disconnect 是正常失败，不应污染任务状态；server exception 应归一化并记录。

metadata normalizer 应是纯函数，可重复调用，不改变输入 map/list。若选择“删除 null value”，文档和测试都写清；若选择“拒绝 null value”，所有入口都统一抛 validation，不要部分删除部分拒绝。

## Artifacts and Notes

审查报告对应的最高风险证据：

    HistoryController:
        timeline(@PathVariable String conversationId, ...)
        historyQueryService.timeline(conversationId, page, size)

    CancellationService:
        conversationService.requireActive(conversationId)
        taskCommandService.cancel(new CancelTaskCommand(new TaskId(taskId), "user_cancelled", conversationId))

    task internal cancellation:
        taskMapper.selectById(Long.parseLong(command.taskId().value()))

    ConfirmationService:
        verifyChallenge(request)  // no proposalId/conversationId binding
        cacheStore.get(key, ConfirmationChallenge.class)
        cacheStore.delete(key)
        createTaskIfPossible(proposal)
        proposalRepository.markConfirmed(proposal, taskId)
        "DAG_PLAN_EXPIRED" -> return "CONFIRMED"

    MessageController:
        heartbeat.start()
        conversationExecutor.execute(...)
        catch (Throwable ex)

    SseHeartbeat:
        future = scheduler.scheduleAtFixedRate(...)
        stop can run before future assignment

    AttachmentCollector / Attachment:
        metadata.put("skuId", image.skuId())
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata)
        catch (RuntimeException ex) -> PACKAGE_REFERENCE_INVALID without cause

目标机制摘要：

    owner binding:
        @CurrentUser -> ownerUserId -> conversation query where id + owner_user_id -> downstream object binding

    confirmation:
        owner proof -> proposal/conversation proof -> atomic challenge consume -> issue/verify token -> proposal CAS to CONFIRMING -> task create idempotently -> proposal CONFIRMED(taskId)

    cancellation:
        owner proof -> parse taskId long -> task belongs to conversation -> cancel only non-terminal -> mapped 404/409/ok

    SSE:
        construct emitter -> start heartbeat -> guarded executor submit -> worker catches Exception only -> finally heartbeat.stop -> emitter completion before lock release

    attachments:
        normalize packageId/sourceRef -> resolver controlled exceptions only -> JSON-safe metadata deep copy -> preserve domain attachment type

    pagination/schema:
        validate config at startup -> bound page/size/offset -> owner indexes -> timestamp defaults

## Interfaces and Dependencies

Conversation controllers should use the auth resolver already provided by `pixflow-infra-auth`:

    public ApiResponse<ConversationView> detail(@CurrentUser AuthPrincipal principal,
                                                @PathVariable String conversationId)

Conversation service should expose owner-aware methods only:

    public ConversationView create(long ownerUserId, CreateConversationRequest request)
    public PageResponse<ConversationView> list(long ownerUserId, long page, long size, boolean includeArchived)
    public ConversationView detail(long ownerUserId, String conversationId)
    public ConversationEntity requireActive(long ownerUserId, String conversationId)
    public void archive(long ownerUserId, String conversationId)

Confirmation challenge store should provide atomic consume:

    public interface ConfirmationChallengeStore {
        Optional<ConfirmationChallenge> consume(String challengeId);
        void put(ConfirmationChallenge challenge, Duration ttl);
    }

Pending proposal repository should support a CAS start state or equivalent guarded confirmation:

    public enum ConfirmationStartResult {
        STARTED,
        ALREADY_CONFIRMED,
        EXPIRED,
        NOT_PENDING
    }

    PendingProposal startConfirmation(String proposalId, String conversationId);
    PendingProposal markConfirmedWithTask(String proposalId, String taskId);
    void markConfirmationFailed(String proposalId, String reason);

Task cancel command should bind task to conversation and requester explicitly:

    public record CancelTaskCommand(
        long taskId,
        String conversationId,
        long requesterUserId,
        String reason
    ) {}

If task module should not depend on auth user id, it can omit `requesterUserId`, but it must still bind `taskId + conversationId` in SQL:

    select * from process_task where id = #{taskId} and conversation_id = #{conversationId}

SSE heartbeat should use atomic future state:

    private final AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();

    void stop() {
        stopped.set(true);
        ScheduledFuture<?> current = future.getAndSet(null);
        if (current != null) current.cancel(false);
    }

Metadata normalizer should be shared inside conversation:

    final class ConversationMetadata {
        static Map<String, Object> normalize(Map<String, ?> input, NullPolicy policy);
        static Object normalizeValue(Object value, NullPolicy policy);
    }

Use it from `MessageSubmitRequest`, `Attachment`, `AttachmentCollector`, and `SseAgentEventSink`.

## Revision Notes

2026-07-09 / Codex: 新建本 ExecPlan。原因是代码审查报告指出 `pixflow-conversation` 存在授权/对象绑定缺口、确认二阶段竞态与错误成功语义、SSE 生命周期泄漏、附件 metadata null/可变性、错误误分类、分页与 schema 边界不足等问题。用户要求中文计划、说明修复机制与思路，并要求重构性修复、不为了迁移式安全保留旧代码。因此本计划选择删除旧无 owner API、重排 confirmation 副作用顺序、增加 challenge 原子消费和绑定、收紧 cancellation task 归属、修复 SSE/lock cleanup、统一 JSON-safe metadata、收紧分页配置与 conversation schema，并记录需要同步 `module/conversation.md`、`infra/auth.md`、`module/task.md`、`frontend/api.md` 与 API 文档索引。
