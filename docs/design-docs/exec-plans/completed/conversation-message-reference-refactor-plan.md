# 将有序素材引用接入 Context、Session 与 Conversation

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`，并作为 `docs/design-docs/exec-plans/backend-architecture-alignment-refactor-plan.md` Milestone 1 的剩余消息链子计划执行。执行者只依赖当前工作树和本文，就应能把 canonical Asset Reference 接入 `pixflow-context`、`pixflow-session`、`pixflow-conversation` 及其直接运行时消费者，并删除旧 ATTACHMENT 身份模型。每个停止点都必须更新本文；改变接口、迁移策略、验证顺序或范围时，必须同步更新四个 living sections 和文末 `Revision Notes`。

## Purpose / Big Picture

完成本计划后，一次用户提交只产生一条 durable USER message。该消息在自己的 metadata 中原子保存用户 mention 的有序 `referenceKey + displayPathSnapshot` 列表，Agent 看到的也是同一条消息及同一顺序的素材引用。刷新历史后，消息仍显示发送时的路径快照；素材后来重命名或删除不会改写历史标签，也不会把显示路径、对象存储 key 或文件字节当作身份。

旧路径会被一次性删除：不再存在 `MessageRole.ATTACHMENT`、独立附件消息、`object://`/`attachment://` 用户输入、`attached_package_id`、Conversation 单包绑定、`AttachmentCollector`、`UserAttachmentInput` 或旧 attachment metadata 常量。可以通过消息提交/历史 API、Session MySQL 集成测试和负向源码搜索看到结果。

## Progress

- [x] (2026-07-18) 阅读 `AGENTS.md`、`PLANS.md`、全部活动执行计划、设计文档索引、后端总计划 Milestone 1、Asset Reference/Context/Session/Conversation/File 设计、ADR 0005、Context Map 和 File/Conversation CONTEXT 文档。
- [x] (2026-07-18) 审计 Context 消息模型与投影、Session JSON 映射和 V1 schema、Conversation 请求/附件/历史/自动配置、Loop/Agent 直接消费者及 Web 消息合同，记录当前差距和用户现有 File 工作。
- [x] (2026-07-18) 创建本中文 ExecPlan，固定 typed message reference、单行原子持久化、后端权威校验、一次性 ATTACHMENT 删除和“先 linting、后测试”的门禁顺序。
- [x] (2026-07-18) Milestone 0：冻结工作树、File resolver 前置条件和开发期 V1 baseline 策略；只消费用户在途 File public API，没有回退 File/Vision 修改。
- [x] (2026-07-18) Milestone 1：Context 已定义有序 `MessageReference`；Session metadata JSON、typed history seam 和 mapper tests 可无损恢复 record/map 两种形态，损坏数据 fail closed。
- [x] (2026-07-18) Milestone 2：Conversation、Loop、Hooks 和 Agent 活动链已原子切换为 references；校验发生在 turn lock 前，Loop 只追加一条 USER message。
- [x] (2026-07-18) Milestone 3：已删除 ATTACHMENT role、Conversation attachment package、Loop Attachment、单包绑定、旧 V1 schema 列和 Web attachment API，不保留兼容读写。
- [x] (2026-07-18) Milestone 4：历史 API 与 Web 合同已改为 typed ordered references；Web 队列保持顺序，当前没有 canonical picker 时发送空 references，不伪造 key。
- [ ] Milestone 5：静态门禁、定向 Java tests、Web tests/build 和负向搜索已完成；Docker daemon 未运行，Session fresh-schema Testcontainers 2 项明确 skipped，因此容器验收仍待可用 Docker 环境复跑。

## Surprises & Discoveries

- Observation: Context 已把 metadata 做成不可变深拷贝，但仍把 ATTACHMENT 定义为第四种消息角色，并提供 `Message.attachment` 与 `MessageStore.appendAttachments`。
  Evidence: `pixflow-context/.../MessageRole.java`、`Message.java`、`MessageMetadata.java` 和 `MessageStore.java` 仍包含完整旧入口；`ContextProjectorTest.leavesAttachmentsUntouched` 把保留独立附件消息当作目标行为。

- Observation: Session 没有 attachment 业务服务，却把旧身份复制到独立列，并暴露按 ATTACHMENT role 查询的 read mapper。
  Evidence: `MessageMapper` 从 metadata 提取 `attachedPackageId`；`MessageEntity`、`MessageReadView`、所有 mapper SELECT/INSERT、V1 migration 和 MySQL 测试 schema 都包含 `attached_package_id`；`MessageReadMapper.findAttachments` 直接查询 `role = 'ATTACHMENT'`。

- Observation: Conversation 当前不是把 references 放进 USER message，而是把顶层 `attachments` 和 `packageId` 展开成 `object://` 附件，再交给 Loop 追加多行消息。
  Evidence: `AttachmentCollector` 读取旧 `PackageReferenceResolver`，`AttachmentMapper` 生成 Context/Loop attachment，`TurnPreparationService` 传递 `List<Attachment>`；`AgentLoop.stream` 先 `appendUser`，再逐条 `appendAttachments`。

- Observation: 旧“会话绑定素材包”也是 attachment 身份模型的一部分，不只存在于 message metadata。
  Evidence: `conversation.package_id`、`ConversationEntity.packageId`、`CreateConversationRequest.packageId`、`ConversationView.packageId` 和 Web conversation/file-index 兼容逻辑仍把一个会话绑定到一个素材包；目标 Conversation 设计只保留 identity、owner、title 和 timestamps。

- Observation: `pixflow-module-file` 当前有用户未提交工作，已经新增 `AssetReferenceResolver`、`ResolvedAssetReference.displayPath` 和默认实现，但 File Milestone 1 尚未整体完成。
  Evidence: `git status --short` 显示 File 计划、自动配置、Permission proof、`api/`、`internal/` 和测试存在修改/未跟踪文件；活动 File 计划明确当前切片可供 Conversation 接入，但 source type、tombstone 等仍待完成。本计划只能消费其 public API，不能重写或回退用户工作。

- Observation: Loop 和 Agent 仍把 attachment 当运行时输入；只修改三个核心模块会导致编译失败或继续生成旧语义。
  Evidence: `AgentTurnRequest`、`AgentLoop`、`UserPromptSubmitPayload` 和 `AgentOrchestrator` 都包含 attachment 字段/映射；Loop 还把 ATTACHMENT 投影成 `[attachment] ...` USER 文本。

- Observation: Web 仍发送顶层 `packageId`/`attachments` 并接受历史 `ATTACHMENT` role，但完整 Asset Reference picker 和 structured mention 仍属于 File/Web 后续能力。
  Evidence: `pixflow-web/src/api/messages.ts`、`runtime/useAgentTurn.ts`、`runtime/useChatSession.ts` 和 `api/attachments.ts` 仍使用旧合同；当前 Web 源码没有 `referenceKey` 或 `displayPathSnapshot` 数据模型。本文只负责消息 API 合同和旧角色清理，不提前实现 File browse/search 或完整 Composer 编辑器。

- Observation: 活动 Lint 计划已清空 Context、Session、File 的 Checkstyle suppression，并要求 Maven reactor 串行运行。
  Evidence: `lint-baseline-remediation-plan.md` 记录 Context/Session 已清理；File 当前工作记录定向 Checkstyle 零违规，但 `-am verify` 曾受桌面命令时限中止。新代码不能恢复 suppression，测试前必须先通过当前批次的严格 linting 门禁。

- Observation: Context 的 tool pair 投影仍读取无人写入的旧 `toolCallIds` metadata，而生产工厂写入 typed `assistantToolCalls`；全量 Context 测试因此把合法 TOOL_RESULT 当作孤儿丢弃。
  Evidence: `ContextProjectorTest` 与 `ContextBudgetServiceTest` 首次运行失败；投影切换为读取 `AssistantToolCall.id` 后，Context 18 项测试全部通过。本次没有增加 legacy 双读。

- Observation: Windows Docker 客户端存在，但 `docker_engine` named pipe 不存在；Session 集成测试按 `disabledWithoutDocker` 跳过，完整 `-am test` 还会被 infra-cache 中未带该保护的 Redis 故障注入测试直接阻断。
  Evidence: `docker info` 返回 daemon pipe not found；重构定向 reactor 为 82 tests、0 failure/error、2 skipped。完整依赖测试在 `RedisDisconnectFailureInjectionTest` 报 Docker environment error 后停止。

- Observation: Loop 全量测试还暴露两项与 message references 无关的既存 runtime-scope 失败，本计划没有扩大语义范围处理它们。
  Evidence: `RuntimeScopeTranslatorTest.subagentEvalMapsToSubagentHooks` 的 `sub_agent`/`subagent` 期望不一致，`RuntimeStateTest.runtimeScopeDefaultsToMain` 期望非 null 默认值；新建的 `AgentLoopMessageReferenceTest` 已通过。

## Decision Log

- Decision: 在 `pixflow-context` 定义 `MessageReference(referenceKey, displayPathSnapshot)`，而不是把它放进 File、Conversation 或新增数据库实体。
  Rationale: Message Reference 是 provider-neutral 的消息组成部分，Context、Session、Loop 和 Conversation 都要共享；Context 只保存不可变字符串对，不解析 canonical grammar，因此不需要新增 `pixflow-context -> pixflow-contracts` 或 `pixflow-context -> pixflow-module-file` 依赖。
  Date/Author: 2026-07-18 / Codex

- Decision: Context metadata 使用一个固定 `references` key 和 typed accessor 保存整个有序列表；不新增 `message_reference` 表，也不把每个引用拆成独立消息行。
  Rationale: 单条 USER row 是 prompt 与 references 的原子事实。JSON metadata 已是 Session 的现有扩展位，可以保持数组顺序、参与 append-only 审计和 compaction rehydrate，又不会引入跨表部分写入。
  Date/Author: 2026-07-18 / Codex

- Decision: Conversation 是用户提交边界的校验者，先用 `PermissionPolicy` 的 `AssetAccess(INSPECT)` 做 deny-first 管理员、会话和资产证明，再调用 File `AssetReferenceResolver` 做 canonical、存在性、readiness 和 current display path 校验；Context 和 Session 不做资源 I/O。
  Rationale: Permission 拥有统一授权顺序，File 拥有当前资产事实，Conversation 拥有用户消息准入。只调 resolver 无法表达 principal/conversation authorization；底层消息/持久化模块也不应知道 PACKAGE、SKU、IMAGE 或资产权限。
  Date/Author: 2026-07-18 / Codex

- Decision: 输入最多 20 个 distinct references；数组顺序原样保存，重复 `referenceKey` 作为无效请求拒绝，不静默重排或去重。持久化的 snapshot 使用 File resolver 返回的安全 current `displayPath`，并要求它与客户端提交的 `displayPathSnapshot` 精确一致。
  Rationale: mention picker 已通过 exact-key exclusion 防重复，服务端拒绝可暴露伪造或陈旧输入；使用后端值避免客户端把任意文本注入模型上下文。rename race 返回可重试的 validation error，客户端刷新候选后再提交。
  Date/Author: 2026-07-18 / Codex

- Decision: Conversation owner 校验先于任何 File 查询；reference 校验在取得 turn lock 前完成，工具/Proposal 使用引用时仍再次解析和授权。
  Rationale: 越权请求不能触碰素材事实，错误引用也不应占用会话锁。提交校验只证明消息准入时的 snapshot，不能替代执行边界的 current-fact revalidation。
  Date/Author: 2026-07-18 / Codex

- Decision: Loop 只追加 `MessageStore.appendUser(prompt, references)` 一次；provider 投影从同一 Message 渲染 display snapshot 和 canonical key，不修改持久化的 prompt content。
  Rationale: 这样投影、滑窗、rehydrate 和历史查询都不可能把 prompt 与 references 分开。模型看到 key 是为了调用工具，历史 content 仍保持用户原文。
  Date/Author: 2026-07-18 / Codex

- Decision: 活动链切换后删除 `loop.Attachment` 和 Conversation attachment package；Agent 不再把用户素材伪装为 `MemoryAttachment`。必要的 reference keys 作为显式 runtime facts 传递，Memory 专属类型留给后端总计划 Milestone 5 处理。
  Rationale: `MemoryAttachment` 是 Memory 内部召回信号，不应继续承担用户素材身份。本文不借删除消息 attachment 重写整个只读 Memory 模型。
  Date/Author: 2026-07-18 / Codex

- Decision: 由于仓库明确处于 development stage，本次直接重写 Session 与 Conversation 的 V1 baseline，删除 `attached_package_id` 和 `conversation.package_id`，并用可重建开发库/新 Testcontainers schema 验证；不增加 V2 兼容 migration、legacy reader 或双写。
  Rationale: 目标是彻底删除旧身份模型。保留旧列再追加 drop migration 会让新环境先创建已废弃 schema，也会留下误导性的生产命中。执行者不得对有价值的共享数据库直接做破坏性重建；发现非 disposable 环境时先记录并停止数据库重建，只完成代码和 fresh-schema 测试。
  Date/Author: 2026-07-18 / Codex

- Decision: 本计划覆盖 Context、Session、Conversation 及为原子切换所必需的 Loop、Hooks、Agent、App 自动配置和 Web 消息 API 类型；完整 File candidate browse/search、Composer structured editor 和上传交互仍由 File/Web 对应里程碑交付。
  Rationale: 不迁移直接消费者就无法删除 ATTACHMENT；但把 Asset picker 和完整 UI 混进消息持久化计划会越过当前 File API 前置并扩大文件所有权冲突。
  Date/Author: 2026-07-18 / Codex

- Decision: 每一批验证固定为 linting 在前、tests 在后；Java 用 `-DskipTests verify` 作为 Checkstyle/SpotBugs/编译门禁，Web 用 `lint` 和 `typecheck` 作为测试前门禁。
  Rationale: 这是用户明确要求，也与活动 Lint 计划的严格门禁一致。linting 失败时先修复，不运行或引用后续测试结果掩盖失败。
  Date/Author: 2026-07-18 / Codex

- Decision: Context tool pair 投影只读取 typed `assistantToolCalls`，不恢复旧 `toolCallIds` metadata 双读。
  Rationale: 当前生产写者已经统一为 `AssistantToolCall`；继续读取无人生产的平行字段会破坏 tool result 原子性，也违背本次一次性重构原则。
  Date/Author: 2026-07-18 / Codex

## Outcomes & Retrospective

生产消息链已经完成一次性切换。Context 用 `MessageReference(referenceKey, displayPathSnapshot)` 和 `MessageMetadata.references` 保存有序列表；Session 保持单条 USER row、提供 typed history reader，并从开发期 V1 baseline 直接删除 `attached_package_id`；Conversation 接受且只接受 `prompt + references`，按 owner、Permission INSPECT、File resolver、stale snapshot、turn lock 的顺序 fail closed；Loop 在 provider 投影时才把 snapshot/key 渲染给模型，持久化 content 保持用户原文。旧 ATTACHMENT role、独立附件消息、Conversation attachment package、Loop Attachment、Conversation 单包绑定、Web paperclip/upload submission 和 legacy schema 均已删除，没有兼容层或双写。

验证严格按静态门禁先于测试执行。最终 30 模块 `mvn -DskipTests verify`、Web lint/typecheck、115 项 Web tests 和 production build 均通过；重构直接触达的 Java reactor 运行 82 tests，80 passed、2 个 Session MySQL/MinIO Testcontainers tests 因 Docker daemon 不可用 skipped。第一、二组旧身份负向搜索均零命中；宽泛 `attachments` 搜索只剩 Agent Memory 内部的 `MemoryAttachment` 召回语义。`git diff --check` 通过。

本子计划的生产实现已经完成，但 fresh-schema 容器证据仍需在 Docker 可用时复跑，因此不把后端总计划 Milestone 1 标记为完成。此外，完整 File source type/tombstone 和 Web canonical picker 仍属于各自活动里程碑；当前 Web 只消费 typed history/发送空 references，不伪造 identity。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。`pixflow-context` 定义 provider-neutral Message、运行期 append-only `MessageStore`、投影、预算与 compaction；它通过 `TranscriptPort` 把持久化倒置给 `pixflow-session`。`pixflow-session` 是 MySQL `message` 表唯一写者，负责 metadata JSON 序列化、seq、rehydrate 和 history read。`pixflow-conversation` 是 HTTP/SSE 消息准入边界，先证明会话 owner，再准备一个 Agent turn。`pixflow-loop` 把 turn request 追加到 MessageStore 并投影为 provider chat messages；`pixflow-agent` 是当前 `AgentTurnRunner` 实现。`pixflow-module-file` 拥有 canonical Asset Reference 的 current facts 和安全 display path。

本计划使用以下术语。Asset Reference 是后端生成的 `package:{id}`、`package:{id}/sku:{encodedSku}` 或 `package:{id}/image:{id}` key。Message Reference 是一条 USER message 内的 `referenceKey + displayPathSnapshot`；key 是身份，snapshot 只是发送时展示标签。Atomic 表示 prompt 和整个 references 数组在一次 Session append 中成为同一个 `message` row，不存在“用户消息已写、附件消息未写”的中间状态。Typed history read 表示 Conversation 收到结构化 references，而不是自行解析 raw metadata JSON 或读取 `attached_package_id`。

开始或恢复实施前，必须按顺序重读：

1. `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`，以及 `docs/design-docs/exec-plans/` 下所有非 `completed/` 活动计划。重点检查 `backend-architecture-alignment-refactor-plan.md`、`pixflow-file-development-plan.md` 和 `lint-baseline-remediation-plan.md` 的最新 Progress、Decision Log 与文件所有权。
2. `docs/design-docs/design.md` 中消息表、Agent turn 和 Asset Reference 段，`docs/design-docs/base/asset-references.md`、`docs/design-docs/base/contracts.md`、`docs/adr/0005-use-canonical-asset-reference-keys.md`。
3. `docs/design-docs/harness/context.md`、`docs/design-docs/harness/session.md`、`docs/design-docs/module/conversation.md`、`docs/design-docs/module/file.md`。
4. `CONTEXT-MAP.md`、`pixflow-conversation/CONTEXT.md`、`pixflow-module-file/CONTEXT.md`。Context 和 Session 当前没有独立 `CONTEXT.md`，以 harness 设计为权威。
5. `docs/design-docs/frontend/api.md`、`frontend/chat.md`、`frontend/files.md` 和 `frontend/upload.md`，用于确认请求/历史合同；不要从 completed 前端计划恢复旧 attachment 行为。
6. 实际触达源码和测试，尤其是 Context model/store/projector、Session mapper/schema/integration tests、Conversation attachment/app/history/config、Loop `AgentTurnRequest`/`AgentLoop`、Hooks `UserPromptSubmitPayload`、Agent `AgentOrchestrator` 和 Web messages/runtime contracts。

若以上权威设计与本文冲突，先在 Decision Log 记录事实和处理方式。accepted ADR 与当前设计优先于 completed 计划；不得因为旧测试仍断言 ATTACHMENT 就恢复旧模型。

## Scope and Non-Goals

范围包括 typed Message Reference、USER message metadata、Context projection 原子性、Session JSON round-trip 与 typed history read、message/conversation V1 baseline 清理、Conversation 输入校验、Loop/Hooks/Agent 直接签名迁移、Conversation/Web history contract、相关自动配置、错误码、测试和精确旧配置删除。

范围不包括 Asset Reference codec/resolver 的 File 内部实现、File candidate browse/search、完整 Web Mention picker/Composer、PACKAGE/SKU expansion、Proposal payload、Task planning、Generated Image publication、Memory recall 重构、数据库线上数据搬迁或多租户。本文消费活动 File 计划交付的 `AssetReferenceResolver`，但不修改其当前用户工作；若该 public API 变化，只在本计划记录并适配调用端。

## Mechanism and Approach

提交链最终应是：

    HTTP MessageSubmitRequest(prompt, ordered references)
      -> Conversation owner proof
      -> MessageReferenceValidator(File AssetReferenceResolver)
      -> turn lock + PreparedTurn
      -> AgentTurnRequest(prompt, List<MessageReference>)
      -> AgentLoop hook dispatch
      -> MessageStore.appendUser(prompt, references)
      -> TranscriptPort.append(one Message)
      -> Session message.metadata JSON
      -> Context projection / provider rendering

`MessageReference` 构造器只执行字符串级不变量：两个字段非空、trim 后不为空、无 ISO control character、列表防御性复制。它不理解 key grammar。`MessageMetadata.withReferences` 把 record 列表放到固定 `references` key；`references()` 同时能读取 JVM 内 record 和 Jackson 反序列化后的 map，遇到缺字段、错误类型或非列表时 fail closed，而不是静默丢引用。`Message.user(content, references)` 和 `MessageStore.appendUser(content, references)` 是唯一用户素材写入口。

Conversation 定义请求 DTO `MessageReferenceInput` 和窄 `MessageReferenceValidator`。请求规范化后，prompt 可以为空的唯一情况是 references 非空；prompt 和 references 同时为空时返回 validation error。Validator 先检查原始数量不超过 20 和 key 不重复，再用服务端可信 principal、conversationId、`EXTERNAL` runtime scope 和非 Plan 模式构造 Permission context，对每个 key 评估 `AssetAccess(INSPECT)`；任一 DENY 都 fail closed。授权后再调用 `AssetReferenceResolver.resolve(referenceKey, AssetUse.INSPECT)`。resolver 必须返回同一 canonical key、可用 resource 和安全 display path；submitted snapshot 与 current display path 不一致时返回 `MESSAGE_REFERENCE_INVALID`，不能继续使用客户端文本。成功结果转换为 Context `MessageReference`，顺序与输入一致。

Session 不建立独立 reference 表。`MessageMapper` 序列化完整 MessageMetadata，反序列化后由 typed accessor恢复顺序。history 查询通过 Session public read service 把 mapper entity 转为一个只含安全字段和 `List<MessageReference>` 的 read view；Conversation 不再接触 raw JSON、Session persistence entity 或 attachment-specific query。compaction marker 和普通消息 seq 规则保持不变，references 随原 USER row 留在 append-only history；destructive compaction 只改变活动链，不改写原行。

Loop 的 provider projection把一条 USER message 渲染为原 prompt 加一个确定性素材段，每行包含 display snapshot 与 canonical key，顺序不变。空 references 不增加任何文本。这个渲染只发生在模型调用前，不回写 `Message.content`。滑窗以 Message 为单位，因此 USER prompt 与 references 永远一起保留或一起被摘要，不存在孤立附件。

## Plan of Work

### Milestone 0：冻结前置、基线和文件所有权

先记录工作树、活动计划和 File public API。不要 stage、移动或重写当前 File 修改；若 `AssetReferenceResolver`、`ResolvedAssetReference.displayPath` 或 `AssetUse.INSPECT` 尚不可编译，先等待/完成活动 File 计划的同一 public 切片，不在 Conversation 内复制 parser 或直接读 File mapper。

确认数据库仅是可重建开发数据。代码按“重写 V1 baseline”实施，自动验收只对 fresh Testcontainers schema 或空本地 schema运行。不得为已执行的共享 Flyway schema自动 drop/rebuild；发现共享数据时把环境事实记入本文并只运行 fresh-schema 测试。

保存旧路径清单和测试基线。必须区分 production、test、migration、`docs/references/`；第三方参考源码的 Attachment 名称不属于生产缺陷。先完成 linting 基线，再运行测试基线，记录实际数量和 skipped。

### Milestone 1：建立 Context/Session typed reference 基础

在 `pixflow-context/src/main/java/com/pixflow/harness/context/model/` 新增 `MessageReference.java`。扩展 `MessageMetadata` 的 `REFERENCES`、`withReferences` 和严格 `references()`；深拷贝逻辑显式保留 MessageReference record。扩展 `Message.user` 与 `MessageStore.appendUser` 的 references 参数，保留无 references 的普通重载给系统恢复/非素材用户消息，但不得增加 attachment 兼容重载。

同步更新 Context 单元测试，证明构造器拒绝空值/控制字符，metadata 对输入列表和嵌套 map 防御性复制，Jackson 形态恢复顺序，ContextProjector 对含 references 的 USER message保持同一个对象/同一顺序，滑窗不会拆分。

在 Session 中让 `MessageMapper` round-trip references，增加 JSON fixture 和 MySQL append/load/compaction tests。新增 public typed history reader/view，内部复用 `MessageReadMapper + MessageMapper`；将 Conversation 后续依赖面限制为该 reader，避免跨模块读 raw JSON。此时先不删除旧列，确保基础能力可独立 lint、测试和 review；新能力尚未成为 HTTP 活动路径，不得增加双写。

### Milestone 2：原子切换活动提交链

在 Conversation app 边界把 `MessageSubmitRequest` 改为 `prompt + List<MessageReferenceInput> references`，彻底删除 `attachments`、顶层 `packageId` 和任意 caller metadata。新增 validator，先使用 Permission `AssetAccess(INSPECT)`，再使用 File public resolver，建立 `MESSAGE_REFERENCE_INVALID` 与明确的 count/duplicate/stale-display/permission-denied/unavailable tests。Conversation owner proof 必须发生在 Permission/File 调用前；validator 成功后才尝试 turn lock。

把 `PreparedTurn`、`AgentTurnRequest` 和 `AgentLoop` 签名改为 Context `MessageReference` 列表。Loop hook payload 的字段从 attachments 改为 ordered references，payload 只包含 `referenceKey` 和 `displayPathSnapshot`；不得包含 owner facts、对象 key 或 resolver view。`AgentLoop` 删除第二次 append，改成一次 `appendUser(prompt, references)`，并增加 provider projection test，断言模型文本包含安全 snapshot/key 且持久化 content 未被拼接。

迁移 `AgentOrchestrator` 对 `AgentTurnRequest` 的读取和 hook/runtime metadata。删除从 user attachments 到 `MemoryAttachment` 的转换；若 recall 仍需显式素材信号，只把有序 reference keys 放进现有 safe metadata，不让 Memory 读取 display text 作为 identity。本里程碑结束时，活动 HTTP -> Agent -> Session 路径已不再创建 ATTACHMENT message。

这一步是跨模块签名原子提交：Conversation、Loop、Hooks、Agent 及其测试必须同时编译。不得提交一个只改 producer 或只改 consumer 的半迁移状态。

### Milestone 3：删除旧身份模型和 schema

确认生产调用已切换后，删除 Context 的 `MessageRole.ATTACHMENT`、`Message.attachment`、`MessageStore.appendAttachments` 和 `MessageMetadata` 的 `ATTACHMENT_TYPE`、`ATTACHMENT_REF`、`ATTACHED_PACKAGE_ID`、`ATTACHMENT_ID`。删除 Loop `Attachment.java` 和专属测试；删除 AgentLoop ATTACHMENT provider branch 与 `attachmentsToMap`。

删除 `pixflow-conversation/.../attachment/` 整个旧 package、Attachment beans、optional collector wiring、attachment properties、旧错误码和对应测试。删除 `conversation.package_id` 以及 entity/create/view/service/Web conversation contract中的单包字段。注意普通 File/Task domain 的 `packageId` 仍是合法资产字段，不能做全仓机械删除。

重写 `pixflow-session/.../V1__create_session_transcript.sql` 删除 `attached_package_id`。同步删除 `MessageEntity`/`MessageReadView` 字段、`MessageMapper` 提取逻辑、所有 read/write SQL 列和 `findAttachments`。更新 Session integration test 的内联 schema/SQL/fake mapper。重写 Conversation V1 baseline 删除 `conversation.package_id`。不保留 V2 drop、nullable fallback、legacy role parser 或 old JSON key reader。

清理 `config/checkstyle/suppressions.xml` 中任何精确指向已删除文件的条目；当前预期这些模块已经零 suppression，若搜索命中说明活动 Lint 计划状态变化，必须在同一提交处理而不能移动行号。

### Milestone 4：对齐 history 和 Web 消息合同

让 Session typed history view和 Conversation `MessageView` 直接暴露 ordered `references`，不暴露 `attachedPackageId`，也不要求 Web 解析 raw metadata 才能获得引用。History 的 role 集合只允许 USER、ASSISTANT、TOOL_RESULT；compaction marker 的现有安全字段保留。

在 `pixflow-web/src/api/messages.ts` 把 send contract 改为 `prompt + references`，把 history references 定义为结构化数组，并删除 ATTACHMENT role、attachedPackageId 和旧 attachment metadata 解析。迁移 `useAgentTurn` 的 queued request shape为 references。删除只服务旧 message submission 的 compatibility adapter/test；不要在本计划中伪造 referenceKey 或从 packageId 拼 key。

完整 Composer mention picker仍依赖 File candidate API，不在本文实现。若 Web 当前页面还没有 canonical candidate source，保持 UI 不生成 references，但 API/client contract和历史渲染必须能消费后端 typed references；不得为了维持旧 paperclip 行为继续发送 packageId。相关 UI能力由活动 File/Web 里程碑接续。

### Milestone 5：验证、清理和总计划交接

按“每个批次先 linting、通过后才 tests”的固定顺序串行验证。不得并行运行 Maven reactor，因为共享 SpotBugs 临时文件会互相争用。定向模块通过后再运行组合 reactor，最后运行前端和全仓门禁。

更新本文 Progress、Surprises、Decision Log、Outcomes 和 Revision Notes，记录实际命令、测试数、skipped 和环境阻断。随后更新 `backend-architecture-alignment-refactor-plan.md`：只有 File resolver前置和本文所有验收都完成时才勾选 Milestone 1；否则写明已完成和剩余部分，不虚报完成。同步更新 `pixflow-conversation/CONTEXT.md`；只有术语或边界实际变化时才更新 Context Map/设计文档，不重写 completed plans。

## Commit Sequence

实施时按以下提交边界推进，每个提交都必须先通过其触达模块的 linting，再通过 tests，且保持 reactor 可编译。

1. Context/Session typed foundation：新增 `MessageReference`、metadata accessor、USER append重载和 Session JSON round-trip tests。旧HTTP路径尚未使用新重载，但不存在双写。
2. Session typed history：新增 public history reader/view并迁移Session内部mapper tests；Conversation仍可暂时使用旧read view，提交后所有模块可编译。
3. Backend active-path switch：在一个原子提交中迁移Conversation请求/validator、PreparedTurn、Loop、Hooks和Agent签名，让活动路径只写一条含references的USER message。不能把producer和consumer拆成两个不可编译提交。
4. Legacy deletion and baseline rewrite：删除Context/Loop/Conversation attachment类型、Session旧列/query、Conversation单包绑定、旧配置/错误码/测试，并重写两个V1 baseline。
5. History/Web contract：切换Conversation到typed history reader，更新MessageView和Web messages/runtime types，删除ATTACHMENT历史role和旧send compatibility adapter。
6. Evidence and handoff：只更新living sections、Context文档和后端总计划真实状态，附lint/test/negative-search证据；不混入新的生产行为。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 执行。每次开始或恢复先运行：

    git status --short
    rg --files docs/design-docs/exec-plans | rg -v "completed[\\/]"
    git diff -- docs/design-docs/exec-plans/backend-architecture-alignment-refactor-plan.md docs/design-docs/exec-plans/pixflow-file-development-plan.md config/checkstyle/suppressions.xml

建立旧路径基线：

    rg -n "MessageRole\.ATTACHMENT|Message\.attachment|appendAttachments|ATTACHMENT_TYPE|ATTACHMENT_REF|ATTACHED_PACKAGE_ID|ATTACHMENT_ID|attached_package_id|attachedPackageId|AttachmentCollector|UserAttachmentInput|PackageBinding|object://|attachment://" --glob "*.java" --glob "*.sql" --glob "*.xml" --glob "*.ts" --glob "*.vue" .
    rg -n "List<Attachment>|harness\.loop\.Attachment|attachmentsToMap|toLoopAttachments|role = 'ATTACHMENT'" --glob "*.java" .

Milestone 0 基线必须先 linting：

    mvn -pl pixflow-context,pixflow-session,pixflow-loop,pixflow-hooks,pixflow-agent,pixflow-conversation -am -DskipTests verify

只有上条成功后才运行基线 tests：

    mvn -pl pixflow-context,pixflow-session,pixflow-loop,pixflow-hooks,pixflow-agent,pixflow-conversation -am test

每个 Java 里程碑都重复同一顺序，必要时先缩小到本次触达模块。例如 Context/Session 批次先运行：

    mvn -pl pixflow-context,pixflow-session -am -DskipTests verify
    mvn -pl pixflow-context,pixflow-session -am test

活动链原子切换批次先运行：

    mvn -pl pixflow-context,pixflow-session,pixflow-loop,pixflow-hooks,pixflow-agent,pixflow-conversation -am -DskipTests verify
    mvn -pl pixflow-context,pixflow-session,pixflow-loop,pixflow-hooks,pixflow-agent,pixflow-conversation -am test

若 Testcontainers MySQL 需要 Windows Docker profile，按仓库当前设计使用已记录的 profile，而不是 skip 测试。先检查：

    docker info

再按项目实际 profile运行 Session integration test。若 Docker 不可用，记录 engine 证据、具体 skipped/failed test 和非容器测试结果；不能把 `-DskipTests verify` 记为测试通过。

Web 合同批次先运行 linting/typecheck：

    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck

只有两条都成功后才运行：

    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build

最终先运行全后端 linting，再运行 tests：

    mvn -DskipTests verify
    mvn verify
    git diff --check
    git status --short

最终负向搜索：

    rg -n "MessageRole\.ATTACHMENT|Message\.attachment|appendAttachments|ATTACHMENT_TYPE|ATTACHMENT_REF|ATTACHED_PACKAGE_ID|ATTACHMENT_ID|attached_package_id|attachedPackageId|AttachmentCollector|UserAttachmentInput|PackageBinding|object://|attachment://" --glob "*.java" --glob "*.sql" --glob "*.xml" --glob "*.ts" --glob "*.vue" .
    rg -n "attachments|UPLOAD_IMAGE|PACKAGE_REFERENCE" pixflow-context pixflow-session pixflow-loop pixflow-hooks pixflow-agent pixflow-conversation pixflow-web/src/api/messages.ts pixflow-web/src/runtime/useAgentTurn.ts --glob "*.java" --glob "*.ts"

第一条在 production、test、migration 和 Web contract 中预期无输出。第二条用于人工分类：Memory 内部 `MemoryAttachment` 或无关文件上传语义可以存在，但用户消息/Loop/Conversation 不得再命中旧 attachment identity。

## Validation and Acceptance

提交一条 prompt 为 `处理这些素材`、references 依次为 PACKAGE 和 IMAGE 的消息。HTTP command 只含 `prompt` 与 ordered `references`。Conversation 对每个 key 先运行 deny-first Permission proof，再调 File resolver，拒绝非 canonical、cross-owner、deleted/non-ready、证明依赖不可用、重复 key、超过 20 项、空 snapshot 和 stale/伪造 snapshot；prompt与references同时为空也必须拒绝。合法请求只向 TranscriptPort append 一条 role=USER 的 Message；数据库只有一条新 row，metadata JSON 的 references 数组顺序与请求一致，content 仍是原 prompt。

Session round-trip 必须证明：append 后 load、一次 compaction、多次 compaction和 history pagination 都保持 references 的 key/snapshot/order；MessageMapper 处理 JVM record 和 JSON map 两种形态；损坏 references metadata fail closed并产生明确错误，不能静默返回空列表。fresh V1 schema不存在 `attached_package_id`，conversation fresh V1 schema 不存在 `package_id`。

Context/Loop 必须证明：投影窗口不会拆分 prompt 与 references；provider看到每个 display snapshot 和 canonical key且顺序稳定；空 references不增加装饰文本；持久化 content不含模型渲染段。删除或重命名素材后，历史 API仍返回原 `displayPathSnapshot`；新 mention candidate使用新 display path。工具使用 key时仍通过 File/Permission重验当前事实，历史 snapshot不能绕过删除或权限。

History API 每条 USER message返回 typed `references`；前端不解析 raw metadata获得引用，不接受 `ATTACHMENT` role或 `attachedPackageId`。旧请求中的 `attachments`、顶层 `packageId`、`UPLOAD_IMAGE`、`PACKAGE_REFERENCE`、`object://` 或 `attachment://` 被 JSON 合同拒绝或不再有可绑定字段，不能被静默忽略后执行。

所有 Java批次必须在测试前有成功的 `-DskipTests verify` 记录；所有 Web tests前必须有成功的 `lint` 和 `typecheck` 记录。最终 Maven tests、Web tests/build、`git diff --check` 和负向搜索都成功，且没有新增 suppression、legacy adapter或 skipped test，才满足验收。

## Idempotence and Recovery

搜索、linting、typecheck、测试和 fresh-schema命令可重复运行。Message append仍以 message id和 `(conversation_id, seq)` 唯一约束幂等；references是同一 row的不可变 metadata，不需要独立重试协议。重复 history load不会修改 snapshot。

不得执行 `git reset --hard`、`git checkout --`、整仓 restore或删除用户工作。开始修改每个 File相关调用方前重查工作树；本计划原则上只消费 File public API。遇到用户同时修改的文件时，在其上做最小补丁或延后，不覆盖。

V1 baseline修改只用于 fresh schema。对有价值或共享数据库不执行 drop；若发现 Flyway checksum已被共享环境采用，停止数据库重建并向用户报告，不能擅自改成兼容双读。代码迁移中途以“Context typed能力已存在但活动HTTP路径尚未切换”为恢复点；活动链切换必须作为一个编译通过的原子批次完成。切换后再删除死代码和schema列，避免 producer/consumer签名不一致。

若严格 verify因桌面124秒时限中止但后台 Maven仍运行，先确认进程和最终输出，不并行启动第二个 reactor。环境超时或 Docker不可用只能记录为阻断，不能写成通过，也不能通过新增 suppression、skip或删除测试恢复绿色。

## Artifacts and Notes

目标持久化示例：

    role = USER
    content = "处理这些素材"
    metadata = {
      "references": [
        {"referenceKey":"package:123","displayPathSnapshot":"summer.zip"},
        {"referenceKey":"package:123/image:991","displayPathSnapshot":"summer.zip / SKU-001 / front-v2.png"}
      ],
      "seq": 42
    }

同一消息的模型投影可以等价于：

    处理这些素材

    Referenced materials:
    - summer.zip [package:123]
    - summer.zip / SKU-001 / front-v2.png [package:123/image:991]

具体标签文字可以按当前 prompt规范调整，但顺序、key和snapshot必须确定性，且不能写回数据库 content。

需要删除的生产路径按 owner归类：

    context: ATTACHMENT role/factory/store API/metadata constants/projector tests
    session: attached_package_id/entity/read view/SQL/findAttachments/test schema
    conversation: attachment package/request fields/collector/mapper/config/errors/tests
    loop/hooks: Attachment/AgentTurnRequest field/second append/hook payload/provider branch
    agent: loop Attachment imports/maps/MemoryAttachment conversion
    web contract: attachments/packageId send shape/ATTACHMENT history role/attachedPackageId

合法但不得误删的相似概念：File/Task/DAG的 `packageId` 是资产域ID；archive upload不是消息attachment；Memory内部 `MemoryAttachment` 在Milestone 5重构前可能仍作为recall实现细节存在；`docs/references/`是只读参考源码。负向搜索必须按路径和语义分类。

## Interfaces and Dependencies

在 `pixflow-context` 定义等价接口：

    public record MessageReference(String referenceKey, String displayPathSnapshot) {}

    public record MessageMetadata(Map<String, Object> values) {
        public static final String REFERENCES = "references";
        public MessageMetadata withReferences(List<MessageReference> references);
        public List<MessageReference> references();
    }

    public final class MessageStore {
        public Message appendUser(String content, List<MessageReference> references);
    }

`MessageReference`不引用 `AssetReferenceKind`，Context POM不新增 Contracts/File依赖。Context无references的 user/system event入口可以保留，但不存在attachment入口。

在 `pixflow-conversation` 定义等价请求和validator：

    public record MessageReferenceInput(
        String referenceKey,
        String displayPathSnapshot
    ) {}

    public record MessageSubmitRequest(
        String prompt,
        List<MessageReferenceInput> references
    ) {}

    public interface MessageReferenceValidator {
        List<MessageReference> validate(
            PermissionPrincipal principal,
            String conversationId,
            List<MessageReferenceInput> references
        );
    }

默认实现依赖 `PermissionPolicy`、`PermissionSubject.AssetAccess`、`AssetAccessMode.INSPECT`，以及 File `AssetReferenceResolver`和 `AssetUse.INSPECT`。Validator签名应接收服务端可信 principal与conversationId，不能从request metadata构造Permission context。它不访问 File mapper/entity/storage location，不扩展 PACKAGE/SKU，也不把display path当identity。

在 `pixflow-loop` 的请求中使用 Context type：

    public record AgentTurnRequest(
        String conversationId,
        PermissionPrincipal principal,
        String prompt,
        List<MessageReference> references,
        CancellationToken cancellation
    ) {}

Hooks payload使用有序结构而不是Map keyed by attachment id，避免Map覆盖或重排。Agent只把referenceKey传给支持canonical key的工具/运行时；displayPathSnapshot只用于可读prompt。

Session public history seam应等价于：

    public interface TranscriptHistoryReader {
        long count(String conversationId);
        List<TranscriptMessageView> page(String conversationId, long offset, long limit);
    }

    public record TranscriptMessageView(
        String id,
        long seq,
        MessageRole role,
        String content,
        List<MessageReference> references,
        String compactionMarker,
        Instant createdAt
    ) {}

实际字段可保留当前安全的tool/task展示字段，但不能暴露attachedPackageId或要求Conversation解析raw metadata。`MessageReadMapper`、`MessageWriteMapper`和entity继续属于Session内部持久化实现。

目标依赖方向保持：

    pixflow-contracts <- pixflow-module-file
    pixflow-context <- pixflow-session
    pixflow-context <- pixflow-loop <- pixflow-agent
    pixflow-context + pixflow-session + pixflow-loop + pixflow-module-file <- pixflow-conversation

不得新增 `context -> contracts/file`、`session -> file/conversation` 或 `loop -> file/conversation` 反向依赖。

## Revision Notes

2026-07-18 / Codex: 完成生产原子切换和旧路径删除。新增 Context-owned MessageReference、Session typed history、Conversation deny-first reference validator、Loop/Hooks/Agent/Web typed 合同；直接重写开发期 V1 baseline，不保留 ATTACHMENT、单包绑定、legacy reader 或双写。静态门禁与定向/Web 测试通过；记录 Docker daemon 不可用导致 2 个 Session container tests skipped，以及范围外 Loop runtime-scope 基线失败。Milestone 1 总计划因 File 剩余工作和容器证据未完成而保持未勾选。

2026-07-18 / Codex: 创建本计划。基于当前工作树确认 File canonical resolver首个切片已存在但仍是用户在途工作；固定Context-owned typed MessageReference、Conversation+File权威校验、Session单row JSON持久化、Loop原子append和typed history seam。把Loop/Hooks/Agent直接消费者、Conversation单包绑定、Session旧列和Web消息合同纳入清理，明确完整File picker/Composer不在范围；所有验证固定先linting/typecheck，后tests。
