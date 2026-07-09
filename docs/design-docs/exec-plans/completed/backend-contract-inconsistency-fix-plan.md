# 修复前后端不一致中的后端部分

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划按仓库根目录的 `PLANS.md` 维护。本文是自包含的中文执行计划：后续执行者只需要当前工作树和本文，就能理解为什么要做、哪些问题属于后端修复范围、应按什么顺序修改、如何验证行为。执行本计划前仍应按 `AGENTS.md` 先阅读 `docs/design-docs/exec-plans/` 中当前执行计划、`docs/design-docs/index.md`，并对照 `docs/design-docs/frontend/`、`docs/design-docs/module/conversation.md`、`docs/design-docs/module/task.md`、`docs/design-docs/module/file.md`、`docs/design-docs/base/common.md` 中的协议与模块边界。

## Purpose / Big Picture

当前 PixFlow 前后端契约不一致中，前端部分已经有执行计划处理 adapter、runtime 和 UI 层的兼容问题；后端仍有几处源头问题会让前端即使改对也拿不到正确数据。完成本计划后，后端会稳定发出前端可消费的 SSE 事件 payload，SSE 长连接会按配置发送 heartbeat，HITL 确认会真正依据提案的执行数量触发 challenge，并在创建任务前消费一次性确认令牌，避免未确认或载荷漂移的提案进入真实执行。

可观察结果是：Agent 工具事件的 `toolName/toolCallId/toolInput/content/externalized` 不再为空；`assistant_message_completed` 事件带有正确 `finalText`、可用 metadata 和 trace 信息；长 SSE 回合每 30 秒左右可收到注释心跳帧；大批量提案会返回 challenge，小批量提案直接返回 token；需要 challenge 的提案只有在 `/submit` 携带正确 `challengeId/challengeAnswer` 且 token claims 与当前提案一致时才创建任务；重复使用同一个确认 token 会失败或命中已有任务幂等结果，而不会绕过确认边界。

## Progress

- [x] (2026-07-06 23:40+08:00) 阅读 `PLANS.md`、`docs/design-docs/index.md`、当前活跃执行计划 `docs/design-docs/exec-plans/frontend-contract-inconsistency-fix-plan.md` 与 `docs/design-docs/exec-plans/infra-ai-clients-completion-plan.md`。
- [x] (2026-07-06 23:50+08:00) 阅读前端设计文档 `docs/design-docs/frontend/README.md`、`api.md`、`transport-api.md`、`stores-runtime.md`、`chat.md`、`tasks.md`、`upload.md`，确认报告中后端相关外部依赖项。
- [x] (2026-07-07 00:05+08:00) 阅读后端设计文档 `docs/design-docs/module/conversation.md`、`docs/design-docs/module/task.md`、`docs/design-docs/module/file.md`、`docs/design-docs/base/common.md`。
- [x] (2026-07-07 00:15+08:00) 检查后端源码，确认 `SseAgentEventSink#value()` 只识别 Map、`MessageController` 未使用 heartbeat 配置、`PendingProposal.from()` 将 `expectedCount` 写死为 0、`ConfirmationService#submit()` 创建任务前未调用 `ConfirmationTokenService.verifyAndConsume(...)`。
- [x] (2026-07-07 00:25+08:00) 撰写本后端修复计划，限定范围为后端源头契约修复、后端测试和协议文档同步。
- [x] (2026-07-06 21:32+08:00) 修复 SSE payload 投影，使 Map、Java record 和普通字符串 payload 都能正确转为前端事件字段。
- [x] (2026-07-06 21:32+08:00) 为 `MessageController` 增加 SSE heartbeat 调度与生命周期清理。
- [x] (2026-07-06 21:32+08:00) 修复 PendingProposal 读视图，使 DAG / imagegen 提案携带真实 `expectedCount` 与可重算的 `payloadHash`。
- [x] (2026-07-06 21:32+08:00) 在确认提交路径串入 `ConfirmationTokenService.verifyAndConsume(...)`，并确保 token 与当前提案 action、conversationId、packageId、payloadHash、expectedCount 一致。
- [x] (2026-07-06 21:32+08:00) 补充后端单元测试和模块级 Maven 验证。
- [x] (2026-07-06 21:35+08:00) 同步设计文档或 API 文档中已过时、互相矛盾的后端协议描述。

## Surprises & Discoveries

- Observation: `docs/design-docs/index.md` 提到的 `docs/design-docs/api.md` 在当前工作树不存在，真实可读 API 权威文档位于 `docs/design-docs/frontend/api.md`。
  Evidence: `Get-Content docs\design-docs\api.md` 返回路径不存在；`docs/design-docs/frontend/api.md` 记录了当前后端暴露给 Vue 前端的接口。

- Observation: `SseAgentEventSink` 是 loop 内部事件到前端 SSE 契约的唯一投影点，但当前 `value(Object payload, String key, Object fallback)` 只识别 `Map`，不识别 Java record。
  Evidence: `pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/SseAgentEventSink.java` 中 `value(...)` 只有 `payload instanceof Map<?, ?>` 分支；`ToolCall` 与 `ToolExecutionResult` 均为 record，因此工具事件字段落入 fallback。

- Observation: 后端配置存在 `pixflow.conversation.sse.heartbeat-interval`，设计文档也要求 30 秒 heartbeat，但当前 `MessageController` 只启动一个 conversation worker 执行 `turnDispatchService.stream(...)`，没有周期发送注释帧。
  Evidence: `ConversationProperties.Sse.heartbeatInterval` 有默认值；`MessageController.submit(...)` 中没有 scheduler / periodic task。

- Observation: HITL submit 当前已经会在 `proposalThreshold.requiresChallenge(proposal)` 为 true 时校验 `challengeId` 与答案，但 `PendingProposal.from(PendingPlan)` 把 `expectedCount` 固定为 0，导致超过阈值的提案也不会进入 challenge。
  Evidence: `PendingProposal.from(...)` 构造 record 时第七个字段写死 `0`；`ProposalThreshold.requiresChallenge(...)` 判断 `proposal.expectedCount() > batchThreshold`。

- Observation: `ConfirmationTokenService` 已经提供一次性 `verifyAndConsume(...)`，但 `ConfirmationService#submit()` 只调用 `issueToken(proposal)`，没有把刚签发的 token 作为真实执行闸门消费。
  Evidence: `pixflow-permission/.../ConfirmationTokenService.java` 提供 `verifyAndConsume(...)`；`ConfirmationService.submit(...)` 在创建任务前没有调用该方法。

- Observation: 会话附件上传后端不存在独立 `/api/conversations/{cid}/attachments` endpoint，这是明确的 V1 产品选择，不应在本后端计划中临时补一个弱契约 multipart 端点。
  Evidence: `frontend/api.md` 写明 `MessageController#submit` 是 JSON-only，附件应复用素材包上传后以 `PACKAGE_REFERENCE` 引用；前端修复计划也选择复用 file 模块上传端口。

## Decision Log

- Decision: 后端修复优先修协议源头，不通过前端继续兜底空字段。
  Rationale: 工具事件 payload 在 `SseAgentEventSink` 被投影为空后，前端无法恢复真实工具名、调用 ID 和结果内容。修后端投影点能让所有前端和未来客户端同时受益。
  Date/Author: 2026-07-07 / Codex

- Decision: SSE heartbeat 在 `MessageController` 层实现，使用与 conversation worker 分开的调度任务，并在 stream complete、error、timeout、completion 回调中取消。
  Rationale: heartbeat 是传输层保活能力，不属于 Agent loop 业务事件；放在 controller / SSE adapter 边界符合 `module/conversation.md` 对 SSE 接出的设计。
  Date/Author: 2026-07-07 / Codex

- Decision: HITL 修复以“真实提案读视图 + 一次性 token 消费”为机制，不把 challenge 判断硬编码在前端或 controller。
  Rationale: 是否需要二次确认取决于当前后端提案实际执行数量和载荷 hash。只有后端在 submit 时重新读取并校验，才能防止提案被改、数量变化或 token 重放。
  Date/Author: 2026-07-07 / Codex

- Decision: 本计划不新增 conversation 私有附件上传端点。
  Rationale: V1 后端已经有素材包上传端口，`MessageController.submit` 是 JSON-only。新增 `/attachments` 会制造第三条素材入口，绕开 file 模块的分片、去重、安全解压和事实源设计。
  Date/Author: 2026-07-07 / Codex

- Decision: 任务进度 topic 保持当前后端连字符频道 `/topic/task-progress-{conversationId}-{taskId}`，后端只补测试与文档，不改为斜杠 topic。
  Rationale: 前端计划已按后端真实实现修复订阅；改后端 topic 会破坏当前 `ConversationProgressBridge` 与已有文档中的新约定。
  Date/Author: 2026-07-07 / Codex

## Outcomes & Retrospective

2026-07-06 / Codex: 完成后端契约修复。`SseAgentEventSink` 现在通过缓存的 record / JavaBean accessor 读取 `ToolCall`、`ToolExecutionResult` 等 payload，并继续只投影前端稳定字段；`MessageController` 使用独立 `conversation-sse-heartbeat` 调度器发送 SSE 注释 heartbeat，并在完成、超时、错误路径取消；`PendingProposalRepository` 在读取提案时重算执行事实，DAG 路径通过 `PendingPlanService.revalidate(...)` + 素材包图片列表 + `BranchExpander` 计算真实 `expectedCount`，imagegen 路径复用 `ImagegenConfirmationSupport` 重算 `payloadHash` 与源图数量；`ConfirmationService.submit(...)` 在创建 task 前签发并立即调用 `ConfirmationTokenService.verifyAndConsume(...)`，用 permission 模块原子消费和校验 action、conversationId、packageId、payloadHash、expectedCount。新增 `SseAgentEventSinkTest` 覆盖 record payload / assistant completed 投影，新增 `ConversationProgressBridgeTest` 保护连字符 topic。验证结果：`mvn -pl pixflow-conversation test` 通过（12 个测试）；`mvn -pl pixflow-conversation -am -DskipTests compile` 通过。全链路 `mvn -pl pixflow-conversation -am test` 未完成，因为上游 `pixflow-eval` 既有测试 `TraceRecorderTest.externalizesLargePayloadAfterSanitizingAndReplaysIt` 失败，conversation 模块未跑到。

## Context and Orientation

PixFlow 后端是 Spring Boot 3 + Maven 多模块项目。与本计划直接相关的模块是 `pixflow-conversation`、`pixflow-permission`、`pixflow-contracts`、`pixflow-module-dag`、`pixflow-module-imagegen`、`pixflow-module-task` 和 `pixflow-common`。

`pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/MessageController.java` 暴露 `POST /api/conversations/{conversationId}/messages`，返回 `text/event-stream`。它创建 `SseEmitter`，把 `SseAgentEventSink` 传给 `TurnDispatchService.stream(...)`，并在异常时调用 `sink.error(ex)`。当前它没有 heartbeat 调度。

`pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/SseAgentEventSink.java` 将 `AgentEvent` 转为 SSE event name 和 JSON data。它是 SSE payload 的唯一投影点。当前工具事件的 payload 若是 Java record，会被 `value(...)` 当作未知对象，导致字段为空。

`pixflow-loop/src/main/java/com/pixflow/harness/loop/event/AgentEvent.java` 表示 Agent loop 对外发出的事件。`payload` 可能是 `Map`、Java record、字符串或其他内部对象。本计划要求 conversation 层只投影稳定前端字段，不泄漏内部对象完整结构。

`pixflow-tools/src/main/java/com/pixflow/harness/tools/ToolCall.java` 和 `ToolExecutionResult.java` 是工具调用和工具结果 record。SSE 需要从这些 record 中取 `toolCallId`、`toolName`、`content`、`metadata` 等字段。

`pixflow-conversation/src/main/java/com/pixflow/module/conversation/app/ConfirmationService.java` 负责 `/challenge` 与 `/submit` 编排。当前 `challenge(...)` 在不需要二次确认时签发 token 并返回 token 字符串；需要二次确认时保存进程内 `ConfirmationChallenge`。`submit(...)` 会校验 challenge 答案，但创建任务前没有消费确认 token。

`pixflow-permission/src/main/java/com/pixflow/harness/permission/token/ConfirmationTokenService.java` 已提供 `issue(TokenClaims)` 和 `verifyAndConsume(ConfirmationToken, PermissionSubject, PermissionContext, int bulkThreshold)`。`verifyAndConsume` 会原子消费 token，并校验 action、conversationId、packageId、payloadHash、expectedCount 和 bulk level。

`pixflow-conversation/src/main/java/com/pixflow/module/conversation/proposal/PendingProposal.java` 是 conversation 统一读取提案的视图。当前它只从 `PendingPlan` 读取 `payloadHash`、`packageId`、payload 和状态，但 `expectedCount` 固定为 0，这是 HITL 不生效的核心后端问题。

`pixflow-module-dag/src/main/java/com/pixflow/module/dag/propose/PendingPlan.java` 是 DAG / imagegen 提案持久化实体。当前表结构没有显式 `expected_count` 列，`note` 中可能携带 `packageId=...`。执行者需要决定是迁移表新增 `expected_count`，还是在 confirm 边界通过 DAG / imagegen 支撑服务重算实际数量。计划采用“优先重算，必要时补列缓存”的策略，避免只信任旧 note 字段。

`pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/confirm/ImagegenConfirmationSupport.java` 已能按 planId 重算 imagegen 提案的 `payloadHash` 和 `expectedCount`。DAG 路径应提供对称的确认支撑能力，或在 conversation 中通过 `PendingPlanService.revalidate(...)` 与 DAG 展开/预检能力获得 expectedCount。

`pixflow-conversation/src/main/java/com/pixflow/module/conversation/progress/ConversationProgressBridge.java` 将 task 进度发布到 `properties.getProgress().getTopicPrefix() + "-" + conversationId + "-" + taskId`。这是后端当前真实 topic 机制，本计划不改其行为，只补测试保护。

几个术语在本文中这样使用。SSE 是 Server-Sent Events，浏览器通过一个 HTTP 响应持续接收 `event:` 与 `data:` 帧。Heartbeat 是 SSE 注释帧，形如以冒号开头的行，用于让代理和浏览器知道连接仍然活着。HITL 是 Human-in-the-loop，意思是真实执行图片处理前必须由用户确认。Challenge 是大批量或高风险提案的二次确认问题。Confirmation token 是服务端保存 claims 的一次性令牌，前端只看到不透明字符串，不能伪造 claims。Payload hash 是提案载荷规范化后的 SHA-256，用于确认“用户确认的就是当前要执行的内容”。

## Plan of Work

第一阶段修复 SSE payload 投影。编辑 `SseAgentEventSink`，把 `value(Object payload, String key, Object fallback)` 扩展为统一的 `PayloadAccessor` 或私有 helper。该 helper 必须支持三类输入：`Map` 按 key 读取；Java record 通过 `RecordComponent` 读取同名 accessor；普通 POJO 可选通过 JavaBean getter 读取；字符串只用于 `ASSISTANT_MESSAGE_COMPLETED` 和 `TRANSITION` 等本来就是文本的事件。对 record 反射结果要做小范围缓存，例如 `ConcurrentHashMap<Class<?>, Map<String, Method>>`，避免每个 token 事件重复扫描。不得把整个 payload 用 ObjectMapper 原样输出到前端，因为那会泄漏内部字段并破坏前端契约。

同一阶段调整 `toPayload(...)` 的字段映射。`TOOL_CALL_READY` 优先读取 `toolName`，其次读取 `name`；`toolCallId` 优先读取 `toolCallId`，其次读取 `id`；`toolInput` 优先读取 `toolInput`，其次读取 `input` 或 `argumentsJson`，如果是 JSON 字符串可解析为对象，解析失败则保留字符串或返回空对象并记录 debug。`TOOL_RESULT` 从 `ToolExecutionResult` record 中读取 `toolCallId`、`toolName`、`content`、`metadata`，并把 `externalized` 从 metadata 中取出，默认 false。`ASSISTANT_MESSAGE_COMPLETED` 的 `messageId` 优先从 metadata 读，其次从 payload record / map 读；如果 payload 是字符串，则它就是 `finalText`，不能再让 `messageId` 走 fallback 造成误判。

第二阶段增加 SSE heartbeat。编辑 `MessageController`，为每次 `submit(...)` 创建 `SseEmitter` 后启动一个周期任务，按 `properties.getSse().getHeartbeatInterval()` 调用 `emitter.send(SseEmitter.event().comment("heartbeat"))` 或等价的注释帧。推荐新增一个小类 `SseHeartbeat` 或 `SseEmitterSession` 来管理 emitter、sink、heartbeat future 和完成回调，避免 controller 方法膨胀。这个类应在 `emitter.onCompletion`、`emitter.onTimeout`、`emitter.onError`、正常 `emitter.complete()` 和 `sink.error(ex)` 后取消 future。heartbeat 发送失败只说明客户端断开，应取消 heartbeat 并让主流按当前错误路径收敛，不应让后台线程无限报错。

第三阶段修复提案读视图的执行数量。先补一个后端确认支撑边界，让 `PendingProposal` 不再负责猜 `expectedCount`。推荐新增接口 `ProposalConfirmationSupport`，暴露 `payloadHash(proposal)`、`expectedCount(proposal)`、`packageId(proposal)` 和 `action(proposal)`，由 DAG 与 imagegen 各自提供实现或适配。imagegen 已有 `ImagegenConfirmationSupport`，可包装成该接口。DAG 路径可先在 `PendingPlanService` 附近新增 `DagConfirmationSupport`，通过读取 `PendingPlan.dagJson`、重校验 DAG、结合 packageId 或 DAG 自身输入范围计算实际 expectedCount。若当前 DAG 模块暂时无法从 dagJson 单独算出数量，则在 `pending_plan` 表新增 `expected_count` 列，并在 `PendingPlanService.enqueue(...)` / `PendingPlanPortAdapter.enqueue(...)` 入库时写入来自工具 handler 或 imagegen plan 的数量；同时保留 confirm 时重算能力作为最终目标。

第四阶段修复 HITL token 消费机制。编辑 `ConfirmationService#submit(...)`：读取当前 proposal 后，先根据真实 expectedCount 决定是否要求 challenge；若需要 challenge，继续执行现有 `verifyChallenge(request)`；随后签发 confirmation token，但不能只签发不用。应立即构造 `ConfirmationToken`、`PermissionSubject` 和 `PermissionContext`，调用 `tokenService.verifyAndConsume(...)`，通过后才能创建任务。`PermissionSubject` 应包含 action、packageId、payloadHash 和 actualCount；`PermissionContext` 应包含 conversationId 等上下文。若当前 `PermissionSubject` / `PermissionContext` 构造器不能直接表达这些字段，按 `pixflow-permission` 的现有类型扩展最小构造方法或静态工厂，保持 permission 模块为校验所有者。`bulkThreshold` 使用 `properties.getConfirmation().getBatchThreshold()`。

第五阶段调整 challenge 与 token 生命周期。`challenge(...)` 在不需要二次确认时可以继续返回 token 字符串，但 submit 端不要信任前端回传 token；V1 前端无需把 token 交回，后端可以在 submit 时重新签发并立即消费。若产品决定前端必须带 token，则需要扩展 `ConfirmationSubmitRequest` 增加 `token` 字段，并让 `/challenge` 的 direct-token 路径返回的 token 在 `/submit` 消费。本计划推荐先使用“submit 内部签发并立即消费”的模式，因为当前前端契约已经是 challengeId / challengeAnswer，不传 token；真实安全性来自 submit 时重读 proposal 和消费一次性 token，而不是前端持有 token。无论采用哪种模式，都必须保证创建任务前经过 `verifyAndConsume(...)` 的同一套 claims 校验。

第六阶段保护后端已经正确的协议，不引入反向修复。不要新增 `/api/conversations/{conversationId}/attachments`。如果有遗留 `AttachmentController` 设计或注释，应改文档说明 V1 conversation 只接受 JSON `MessageSubmitRequest.attachments`，素材上传走 `POST /api/files/packages` 或分片上传协议。`ConversationProgressBridge` 的连字符 topic 保持不变，但需要补测试断言频道为 `task-progress-{cid}-{tid}`。`HistoryController`、`ConversationController` 的分页行为按各自真实 controller 保持，若要统一，必须另起计划评估 task 模块 `PageQuery` 的 0-based 行为，不在本次后端源头修复中混改。

第七阶段补测试。为 `SseAgentEventSink` 写纯单元测试，覆盖 Map payload、record payload、字符串 payload、tool call、tool result、assistant completed 和 error 帧。为 `MessageController` 写使用 fake `TurnDispatchService` 的 MockMvc 或直接 controller 测试，验证至少收到一次 heartbeat 注释帧，并在完成后不再继续发送。为 `ConfirmationService` 写单元测试：`expectedCount` 小于等于阈值时 direct-token 路径可 submit 创建任务；大于阈值时 `/challenge` 返回 challenge；submit 缺 challengeId 返回 `PROPOSAL_CHALLENGE_FAILED`；错误答案返回 `PROPOSAL_CHALLENGE_FAILED`；正确答案会调用 `verifyAndConsume` 并创建任务；payloadHash / expectedCount 不一致时 permission 错误阻止任务创建。为 `ConversationProgressBridge` 写单元测试保护连字符 topic。

第八阶段同步文档。更新 `docs/design-docs/frontend/api.md` 或后续对应后端 API 文档中的过时段落：删除“后端会发送 heartbeat”与“未发送 heartbeat”之间的矛盾，只保留修复后的事实；明确 `SseAgentEventSink` 支持 record payload；明确 `/attachments` 端点不存在；明确 HITL token 在后端 submit 边界校验与消费；明确 `PendingProposal.expectedCount` 来自真实提案数量，不再硬编码 0。若修改活跃执行计划，应在其 `Revision Notes` 写明原因。

## Concrete Steps

从仓库根目录 `D:\study\PixFlow` 开始。先确认工作区状态，避免覆盖他人改动：

    git status --short

搜索后端不一致源头：

    rg -n "SseAgentEventSink|heartbeatInterval|expectedCount\(\)|verifyAndConsume|new ConfirmationToken|task-progress-|attachments" pixflow-conversation pixflow-permission pixflow-contracts pixflow-module-dag pixflow-module-imagegen pixflow-module-task

第一组编辑 SSE 投影：

    pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/SseAgentEventSink.java
    pixflow-conversation/src/test/java/com/pixflow/module/conversation/api/SseAgentEventSinkTest.java

预期修改后，`value(...)` 不再只处理 Map；测试中传入 `new com.pixflow.harness.tools.ToolExecutionResult(...)` 时，`tool_result` payload 能包含非空 `toolCallId` 与 `content`。

第二组编辑 heartbeat：

    pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/MessageController.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/api/SseHeartbeat.java 或等价小类
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/config/ConversationAutoConfiguration.java
    pixflow-conversation/src/test/java/com/pixflow/module/conversation/api/MessageControllerSseTest.java

如果新增 scheduler bean，建议在 `ConversationAutoConfiguration` 中注册有名字的 `ScheduledExecutorService`，线程名使用 `conversation-sse-heartbeat`，并设置 daemon。测试中可把 heartbeat interval 配成极短，例如 50ms。

第三组编辑 HITL 提案数量与 token 消费：

    pixflow-conversation/src/main/java/com/pixflow/module/conversation/app/ConfirmationService.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/proposal/PendingProposal.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/proposal/PendingProposalRepository.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/proposal/PendingPlanPortAdapter.java
    pixflow-module-dag/src/main/java/com/pixflow/module/dag/propose/PendingPlanService.java
    pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/confirm/ImagegenConfirmationSupport.java
    pixflow-permission/src/main/java/com/pixflow/harness/permission/PermissionSubject.java
    pixflow-permission/src/main/java/com/pixflow/harness/permission/PermissionContext.java

预期修改后，`PendingProposal.from(...)` 不再出现 `expectedCount` 写死 0 的逻辑；`ConfirmationService.submit(...)` 在 `createTaskIfPossible(...)` 之前调用 `tokenService.verifyAndConsume(...)` 或一个清晰命名的私有方法间接调用它。

若决定新增数据库列，增加 migration：

    pixflow-module-dag/src/main/resources/db/migration/V2__add_pending_plan_expected_count.sql

迁移应只添加列，不破坏已有数据。旧数据可以默认 0，并在 confirm 时尽量重算；无法重算的旧提案应返回明确错误，要求用户重新生成提案。

第四组补协议保护测试：

    pixflow-conversation/src/test/java/com/pixflow/module/conversation/app/ConfirmationServiceTest.java
    pixflow-conversation/src/test/java/com/pixflow/module/conversation/progress/ConversationProgressBridgeTest.java
    pixflow-conversation/src/test/java/com/pixflow/module/conversation/proposal/PendingProposalTest.java

完成编辑后运行 conversation 相关测试：

    mvn -pl pixflow-conversation -am test

如果修改了 permission 类型或 contracts，运行：

    mvn -pl pixflow-permission,pixflow-contracts,pixflow-conversation -am test

如果修改了 DAG / imagegen 确认支撑，运行：

    mvn -pl pixflow-module-dag,pixflow-module-imagegen,pixflow-conversation -am test

最后运行 app 级装配测试，确保新增 bean 不破坏 Spring 上下文：

    mvn -pl pixflow-app -am test

## Validation and Acceptance

SSE payload 验收：构造一个 `AgentEvent`，type 为 `TOOL_CALL_READY`，payload 为 Java record 或 `com.pixflow.harness.tools.ToolCall` 等价对象。调用 `SseAgentEventSink.emit(...)` 后，发送给 emitter 的 JSON data 中应包含真实 `toolName`、`toolCallId` 和 `toolInput`，而不是空字符串和空对象。构造 `TOOL_RESULT`，payload 为 `ToolExecutionResult.success("call-1", "search", "ok", Map.of("externalized", false))`，输出 data 应包含 `toolCallId="call-1"`、`content="ok"`、`externalized=false`。

assistant completed 验收：构造 `ASSISTANT_MESSAGE_COMPLETED`，`event.text()` 为最终文本，metadata 带 `traceId` 和 `turnNo`。输出 event name 必须是 `assistant_message_completed`，payload 必须包含 `finalText`、`traceId`、`turnNo`；`messageId` 缺失时可以为空字符串，但不能因为 payload 是字符串而把 `finalText` 丢失。

SSE heartbeat 验收：用测试配置把 heartbeat interval 设为 50ms，fake `TurnDispatchService` 阻塞 150ms。调用 message endpoint 后，响应流中应能观察到至少一个 SSE 注释 heartbeat 帧，随后 fake service 正常结束时流关闭。测试还应断言完成后 heartbeat future 被取消，避免后台线程继续写已完成 emitter。

HITL challenge 验收：构造一个 `PendingProposal.expectedCount = batchThreshold + 1` 的 pending proposal。调用 `challenge(conversationId, proposalId)` 应返回 `needChallenge=true` 和非空 `challenge.challengeId`。直接调用 submit 且 body 为空，应抛 `PROPOSAL_CHALLENGE_FAILED` 或等价 HTTP 400。传错误答案，应抛 `PROPOSAL_CHALLENGE_FAILED`。传正确答案，应继续进入 token 校验和任务创建。

HITL token 验收：在 submit 创建任务前，fake `ConfirmationTokenService` 应记录到一次 `verifyAndConsume(...)` 调用，subject 中的 action、packageId、payloadHash、actualCount 与当前 proposal 一致。若 fake token service 抛 `CONFIRMATION_COUNT_MISMATCH` 或 `CONFIRMATION_PAYLOAD_MISMATCH`，`TaskCommandService.createAndEnqueue(...)` 不得被调用，pending proposal 不得标记 CONFIRMED。

小批量验收：构造 `expectedCount <= batchThreshold` 的 proposal。`challenge(...)` 可返回 `needChallenge=false` 和 token 字符串；`submit(...)` 不要求 challengeAnswer，但仍必须执行 token claims 校验并创建任务。返回 `ConfirmationSubmitResponse` 应为 `{ proposalId, taskId, status: "CONFIRMED" }`。

进度 topic 验收：`ConversationProgressBridge.onProgress("c1", "t1", event)` 应调用 `ProgressNotifier.publish("task-progress-c1-t1", event)` 或根据配置前缀生成同等连字符频道。不要生成 `task-progress/c1/t1`。

自动化验收至少运行：

    mvn -pl pixflow-conversation -am test
    mvn -pl pixflow-permission,pixflow-contracts,pixflow-conversation -am test
    mvn -pl pixflow-module-dag,pixflow-module-imagegen,pixflow-conversation -am test

如果这些通过且 app 装配受影响，再运行：

    mvn -pl pixflow-app -am test

## Idempotence and Recovery

本计划改动应可重复执行。SSE payload 投影 helper 是纯函数，不应修改 payload 对象。record accessor 缓存只按 Class 缓存只读 Method，不存业务数据，不影响测试顺序。heartbeat future 必须在所有完成路径取消；重复取消应无副作用。

HITL submit 的任务创建必须保持幂等。`TaskCommandService.createAndEnqueue(...)` 已通过 `idempotencyKey = "proposal:" + proposalId` 防重复创建；本计划不能破坏这个语义。若 token 已消费但网络断开，用户重复 submit 时可能遇到 token 失效和任务已创建之间的竞态。实现时应优先查询 proposal 是否已 CONFIRMED 并带 taskId；若已确认，可按幂等成功返回现有 taskId，不能创建第二个任务。若 proposal 仍 PENDING 但 token 已消费，应返回明确确认失效错误，要求用户重新 challenge。

数据库迁移必须向后兼容。新增 `pending_plan.expected_count` 时给默认值 0，不删除旧字段，不改旧索引。旧 PENDING 提案若无法重算数量，应在 submit 时返回可理解错误并提示重新生成方案，而不是按 0 静默放行大批量任务。

不新增 `/api/conversations/{conversationId}/attachments`。如果测试或旧代码仍引用该路径，应迁移到 file package 上传或删除无效测试。不要为了兼容旧前端而绕过素材包上传安全链路。

如果某个设计文档与源码真实行为冲突，以当前源码和本计划修复后的行为为准，并在文档 `Revision Notes` 记录修改原因。尤其是 heartbeat、task 分页基准、attachment endpoint 这几处历史文档存在矛盾，更新时要写清楚“修复后事实”。

## Artifacts and Notes

报告中属于后端源头修复的项摘要：

    A7: SSE tool_* payload 为空。后端 SseAgentEventSink 只识别 Map，不识别 record。
    D6: ConversationProperties.sse.heartbeatInterval 存在但 MessageController 未发送 heartbeat。
    D3/D4: HITL 真实安全边界不完整。expectedCount 写死 0，确认 token 未在 submit 创建任务前 verifyAndConsume。
    待保护项: ConversationProgressBridge 使用连字符 topic，这是后端真实契约，应补测试而不是改成斜杠。
    待保持项: 后端没有 conversation 私有附件上传端点，V1 应通过 file package 上传后在消息中 PACKAGE_REFERENCE 引用。

当前源码证据：

    pixflow-conversation/.../SseAgentEventSink.java
    private static Object value(Object payload, String key, Object fallback) {
        if (payload instanceof Map<?, ?> map && map.containsKey(key)) {
            Object value = map.get(key);
            return value == null ? fallback : value;
        }
        return fallback;
    }

    pixflow-conversation/.../PendingProposal.java
    return new PendingProposal(..., plan.getPayloadHash(), 0, ...);

    pixflow-conversation/.../ConfirmationService.java
    if (proposalThreshold.requiresChallenge(proposal)) {
        verifyChallenge(request);
    }
    issueToken(proposal);
    String taskId = createTaskIfPossible(proposal);

目标行为示意：

    submit(proposal):
      proposal = reloadPendingProposal(proposalId)
      facts = proposalConfirmationSupport.resolve(proposal)
      if facts.expectedCount > threshold:
          verifyChallenge(request.challengeId, request.challengeAnswer)
      token = tokenService.issue(claimsFrom(facts))
      tokenService.verifyAndConsume(token, subjectFrom(facts), contextFrom(conversationId), threshold)
      taskId = taskCommandService.createAndEnqueue(...)
      proposalRepository.markConfirmed(proposal, taskId)
      return { proposalId, taskId, status: "CONFIRMED" }

## Interfaces and Dependencies

SSE 投影 helper 最终应支持如下语义：

    Object readPayloadValue(Object payload, String key, Object fallback)

输入规则：

    Map: map.get(key)
    Java record: 调用同名 record accessor
    POJO: 可选调用 getXxx / isXxx
    String: 不按 key 读取，只由事件分支显式使用
    null: fallback

`TOOL_CALL_READY` 输出 payload：

    {
      "toolName": "...",
      "toolCallId": "...",
      "toolInput": { ... }
    }

`TOOL_STARTED` 输出 payload：

    {
      "toolCallId": "..."
    }

`TOOL_RESULT` 输出 payload：

    {
      "toolCallId": "...",
      "content": "...",
      "externalized": false
    }

`ASSISTANT_MESSAGE_COMPLETED` 输出 payload：

    {
      "finalText": "...",
      "messageId": "",
      "traceId": "...",
      "turnNo": 0
    }

SSE heartbeat 行为：

    interval = pixflow.conversation.sse.heartbeat-interval
    frame = SSE comment "heartbeat"
    lifecycle = start after emitter creation; cancel on completion, timeout, error, normal complete

HITL facts 建议引入的内部 record：

    record ProposalExecutionFacts(
        String proposalId,
        ConfirmationAction action,
        String conversationId,
        String packageId,
        String payload,
        String payloadHash,
        int expectedCount
    ) {}

确认提交内部接口建议：

    interface ProposalConfirmationSupport {
        ProposalExecutionFacts resolve(PendingProposal proposal);
    }

Permission 调用所需信息：

    ConfirmationToken token = tokenService.issue(new TokenClaims(
        facts.action(),
        facts.conversationId(),
        facts.packageId(),
        facts.payloadHash(),
        facts.expectedCount() > threshold ? ConfirmationLevel.BULK : ConfirmationLevel.NORMAL,
        facts.expectedCount(),
        issuedAt,
        expiresAt,
        nonce
    ));

    tokenService.verifyAndConsume(
        token,
        PermissionSubject.forConfirmation(
            facts.action(),
            facts.packageId(),
            facts.payloadHash(),
            facts.expectedCount()
        ),
        PermissionContext.forConversation(facts.conversationId()),
        threshold
    );

若 `PermissionSubject` / `PermissionContext` 当前没有这些工厂方法，按最小范围新增。不要让 conversation 绕过 permission 自己比较 claims，因为 `verifyAndConsume(...)` 的原子消费语义属于 permission 模块。

## Revision Notes

2026-07-07 / Codex: 新建本文档。原因是用户要求阅读前端设计文档和前后端不一致报告，并按 `PLANS.md` 撰写“修复前后端不一致中的后端部分”的中文计划，说明修复机制和思路。本计划聚焦后端源头问题：SSE record payload 投影、SSE heartbeat、HITL expectedCount 与 confirmation token 消费；同时明确不新增 conversation 私有附件上传端点、不改变任务进度连字符 topic。

2026-07-06 / Codex: 实施本文档。原因是完成后端源头修复并同步实际结果：SSE record payload 投影、SSE heartbeat、HITL expectedCount 与 confirmation token 消费均已落地；DAG expectedCount 采用 confirm 边界重算，未新增数据库列；仍不新增 conversation 私有附件上传端点，任务进度 topic 保持连字符。
