# 按 canonical Asset Reference、临时 Proposal 与可恢复执行模型完成后端架构总重构

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。执行者只依赖当前工作树和本文，就应能把 2026-07-17 已更新的后端目标设计落实为可运行系统。每个停止点都必须更新本文；改变契约归属、模块依赖、数据库迁移、阶段顺序、兼容策略或验收命令时，必须同步更新 `Progress`、`Surprises & Discoveries`、`Decision Log`、`Outcomes & Retrospective` 和文末 `Revision Notes`。

## Purpose / Big Picture

完成本计划后，管理员可以在对话中用统一的 `referenceKey` 提及素材包、SKU、原图或生成图；Agent 只能发布已经完整校验的 Proposal，用户对每个 Proposal 单击一次确认后直接创建任务，不再经过 challenge、确认令牌、持久化 `pending_plan` 或二次确认。确定性图片处理和单图重绘都以稳定 Work Unit、Execution Epoch 和 fenced commit 执行，成功结果发布为拥有新 `imageId` 的 Generated Image；清除任务或 Activity 不会删除已经发布的图片。

商品视觉理解将成为可恢复的 Product Visual Facts 作业，而不是通用视觉子 Agent 或上传期文案生成。Memory 和 Vector 只做已有事实召回，不接受对话、任务、Hook 或 Rubrics 写回。Rubrics 只做离线、证据约束的四态 Criterion 评估，不输出不透明质量分，不进入 Agent 主循环，也不把人工或模型判定写入 Memory。

用户可通过一条端到端路径观察结果：上传素材后，Materials 返回后端生成的 canonical references；在 Chat 中 mention 一张图并提交单图重绘 Proposal；单击一次确认产生一个 Task；任务完成后 Outputs 出现一个新的 Generated Image reference；删除任务记录后该 Generated Image 仍存在并可再次 mention。开发者还可以通过模块测试、真实 MySQL/Redis/MinIO/RocketMQ 或相应 Testcontainers 契约测试，观察旧 epoch 无法提交、Redis 临时引用不能冒充 checkpoint、Product Visual Facts 不返回历史指纹、Memory 查询无副作用、Rubrics 缺证据时返回 `INCONCLUSIVE`。

## Progress

- [x] (2026-07-17) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、当前活动执行计划、总体设计、Context Map、系统 ADR 和本次变更涉及的模块设计。
- [x] (2026-07-17) 核对提交 `0faa171` 的后端设计变更，并建立模块依赖 DAG、可并行工作流和关键契约切口。
- [x] (2026-07-17) 核对当前工作树，确认 `execution-domain-refactor-plan.md`、`linter-adoption-plan.md`、`rubrics-criterion-verdict-refactor-plan.md` 正在由用户移动到 `exec-plans/completed/`，活动计划只剩 `lint-baseline-remediation-plan.md`。
- [x] (2026-07-17) 创建本后端总重构 ExecPlan，固定目标规范优先级、阶段顺序、每阶段必读文档、兼容策略和验收闭环。
- [ ] Milestone 0：完成实现基线审计、文档冲突收敛、公开契约冻结和架构测试骨架。
- [ ] Milestone 1：落地 canonical Asset Reference、File 解析边界和 USER message references，删除 ATTACHMENT 身份模型。
- [ ] Milestone 2：落地单一管理员、deny-first Permission 和 Conversation 所属的临时 Proposal，删除确认令牌与 durable pending plan。（2026-07-17：Auth、Permission 与 Proposal 子范围已完成；已接五类 current-facts proof、可信 runtime context、发布二次授权、空 body confirm/reject、进程内 Proposal CAS、Task proposalId replay，并删除 token/challenge/pending_plan 全链路。总里程碑仍等待 Milestone 1 的 canonical Asset Reference 工具合同完成；当前 Imagegen 内部仍从已校验的 packageId/imageId 生成 key，不能把总链路虚报完成。）
- [ ] Milestone 3：对齐 DAG、Imagegen、Task、State、Storage、Cache 与 Image 执行链，并把成功结果发布为独立 Generated Image。
- [ ] Milestone 4：把 Vision 替换为可恢复 Product Visual Facts 作业和唯一 Agent lookup tool。
- [ ] Milestone 5：把 Memory、Vector、Hooks 与 Agent 收窄为只读召回和视觉事实消费，删除全部在线写回路径。（2026-07-17：Vector/Memory 子范围已完成，唯一运行时能力为三方法 `VectorSearch`，在线 ingest/reinforcement/lifecycle/rebuild 与旧 hook 已删除；canonical Asset Reference 和视觉事实消费仍待本总计划后续切片。）
- [ ] Milestone 6：完成 Rubrics 四态 Criterion、Evidence Pack、dataset/regression 和离线自动化边界，不实现 Promotion。
- [ ] Milestone 7：完成 App 组合根、前端合同、旧路径清理、故障注入和全仓验收。

## Surprises & Discoveries

- Observation: 最新 Rubrics 目标设计与已移动到 completed 的旧 Rubrics ExecPlan 存在实质冲突。最新设计明确没有自动或人工审核后的 Memory 写入，而旧计划仍包含 Promotion、`ReviewedInsightService` 和 `Rubrics -> Memory` 依赖。
  Evidence: `docs/design-docs/module/rubrics.md` 的 “Evaluation does not become memory” 与 Memory boundary；`docs/design-docs/module/memory.md` 的公共 API 只有 `prepareContext`；`docs/design-docs/exec-plans/completed/rubrics-criterion-verdict-refactor-plan.md` 的旧 Milestone 7 仍描述 Promotion。

- Observation: 代码仍保留大量被新设计直接删除的生产路径，不能把本次工作当成少量 DTO 对齐。
  Evidence: 生产源码仍命中 `ConfirmationTokenStore`、`RedisConfirmationTokenStore`、`PendingPlanPort`、`pending_plan` migration、`MessageRole.ATTACHMENT`、`VisionService`、`ProductCopyExtractor`、`MemoryIngestService.ingestAsync` 和 `reinforce`。

- Observation: `pixflow-contracts` 当前含 confirmation 与 pending-plan 共享类型，但最新契约明确 Proposal 属于 Conversation，不能因为 DAG、Imagegen、Conversation 都参与就把 Proposal 放入中立共享模块。
  Evidence: `docs/design-docs/base/contracts.md` 的 Proposal ownership 章节明确要求依赖指向属主模块公开 API 或属主定义的窄端口。

- Observation: Task 成功发布图片需要 File 能力，但 `module/task` 的目标边界禁止直接依赖 File 内部实现。
  Evidence: `docs/design-docs/module/task.md` 要求 Task 只调用 `GeneratedAssetPublicationPort`；File 分配新 `imageId`、记录 lineage 并返回 canonical IMAGE reference；组合根负责接线。

- Observation: 当前活动的 `lint-baseline-remediation-plan.md` 仍在修改 Checkstyle suppression 和生产源码，本计划若同时大面积删除或重写相同文件会造成行号基线和所有权冲突。
  Evidence: 当前工作树包含 common、file 和 `config/checkstyle/suppressions.xml` 的未提交修改；活动 Lint 计划要求按文件原子删除 suppression 并串行运行 Maven reactor。

- Observation: 已完成的 Execution Domain 计划证明 Canonical DAG、Work Unit、Execution Epoch、Pixel Budget 和 Derived Retry 的主体已有实现，但最新架构仍改变了素材身份、Proposal 所有权和成功结果发布边界。
  Evidence: `docs/design-docs/exec-plans/completed/execution-domain-refactor-plan.md` 的 completed milestones；当前源码仍存在 durable pending plan、旧 Imagegen 多图输入和未统一的 Asset Reference 消费方。

## Decision Log

- Decision: 目标规范优先级固定为已接受 ADR 与 2026-07-17 当前 `design.md`、`base/`、`infra/`、`harness/`、`module/`、`agent/` 设计，其次是 Context Map 与模块 `CONTEXT.md`，completed ExecPlan 仅作为历史实现证据。
  Rationale: completed 计划记录当时实施事实，不能覆盖之后明确接受的新架构决策；这也解决 Rubrics Promotion 冲突。
  Date/Author: 2026-07-17 / Codex

- Decision: 本期不实现任何 Rubrics Promotion 或 Memory 写入端口，`module/rubrics` 与 `module/memory` 保持零依赖。
  Rationale: 最新总体设计、Rubrics 设计和 Memory 设计都明确删除该产品能力；恢复旧计划会重新引入被否决的数据污染路径。
  Date/Author: 2026-07-17 / Codex

- Decision: `pixflow-contracts` 只保留真正中立、跨边界的 Asset Reference 与基础形状；Proposal 模型和状态属于 `pixflow-conversation`，DAG 与 Imagegen 只暴露各自的 validated payload。
  Rationale: 共享模块不能成为没有属主的业务 DTO 仓库。Proposal 的创建、过期、确认、拒绝和 task binding 都是 Conversation 语义。
  Date/Author: 2026-07-17 / Codex

- Decision: Proposal 工具的组合适配器放在 `pixflow-app` 或 Conversation 的 application adapter 中，依次调用 DAG/Imagegen 的 validation API 与 Conversation 的 publication API；不让 DAG 或 Imagegen 持久化 Proposal，也不让 `harness/tools` 理解 Proposal 业务类型。
  Rationale: 这样同时满足 Tool Registry 的 handler SPI、Conversation 的 Proposal 所有权以及 DAG/Imagegen 不反向依赖 Conversation 内部实现的约束。
  Date/Author: 2026-07-17 / Codex

- Decision: Task 与 File 通过 Task-owned `GeneratedAssetPublicationPort` 和 File public publication service 在 `pixflow-app` 接线，不新增 `task -> file` 内部实现依赖。
  Rationale: Task 拥有 fenced SUCCESS 和执行事实，File 拥有 Asset Image 身份与生命周期；端口隔离能防止表和实体互相泄漏。
  Date/Author: 2026-07-17 / Codex

- Decision: 采用开发期一次性切换，不保留 confirmation token、pending plan、ATTACHMENT role、旧 Imagegen 多图合同、通用 Vision Q&A 或 Memory 写回兼容层。
  Rationale: 仓库处于开发阶段，新旧路径并存会形成两个身份源、两个确认模型和两个恢复真相源。数据库 migration 可以保留历史文件校验和，但新基线和生产代码只实现新模型。
  Date/Author: 2026-07-17 / Codex

- Decision: 并行开发只发生在公开接口已冻结且没有直接依赖的工作流之间；同一 Maven reactor 的验证命令串行运行。
  Rationale: DAG 与 Vision、Vision 与 Memory、Rubrics 与 Vision/Memory 可以并行；Task 必须等待 DAG/Imagegen/State 接口。串行 Maven 避免共享 `target/spotbugsTemp.xml` 冲突。
  Date/Author: 2026-07-17 / Codex

## Outcomes & Retrospective

当前仅完成调研和总计划，没有实施生产重构。实施完成后，本节必须逐个里程碑记录实际新增、删除和保留的公开接口、数据库表或 migration、各测试命令的通过数量、真实依赖故障注入结果、与目标设计的任何偏差，以及用户可观察的端到端行为。不能以“编译成功”代替 Proposal、执行 fencing、资产发布、视觉事实、只读召回和 Rubrics 证据语义的行为证明。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。根 `pom.xml` 管理 29 个 Java 子模块，连同根项目构成 30 项 Maven reactor；前端位于 `pixflow-web`。模块名与目录名大致一致，例如 `pixflow-module-file` 是 Asset Library，`pixflow-module-dag` 是确定性 DAG 执行能力，`pixflow-module-task` 是有状态异步执行外壳，`pixflow-conversation` 是消息和 Proposal 决策边界，`pixflow-agent` 是 Agent 组合层。

开始任何里程碑前，执行者必须先阅读仓库根 `AGENTS.md`、本计划、`docs/design-docs/exec-plans/` 下当时所有活动计划、`docs/design-docs/index.md`、`docs/design-docs/design.md`、`CONTEXT-MAP.md` 和本里程碑列出的专题文档。若某个模块存在 `CONTEXT.md` 或模块 ADR，也必须阅读。活动计划优先用于判断当前工作所有权，不能覆盖较新的 accepted ADR 和目标设计；发现冲突时先记录在本计划并按 Decision Log 的规范优先级处理。

本计划使用以下术语。Asset Reference 是后端生成的 canonical key，标识 PACKAGE、SKU 或 IMAGE，不包含对象存储 key、展示名或字节。Proposal 是一次已经完整校验、等待用户决定的副作用请求，属于 Conversation，只在当前运行时临时存在。Work Unit 是 Task 独立调度和 checkpoint 的最小单位。Execution Epoch 是 Task 的单调所有权代次，旧 worker 即使完成外部调用也不能提交新 epoch 的结果。Runtime Reference 是 Redis 指向临时对象的可丢引用，不是 checkpoint。Product Visual Facts 是从商品图直接观察得到、带指纹和限制的持久事实，不包含营销推断或生成指令。Criterion Verdict 是 Rubrics 对一个原子要求给出的 `PASS`、`FAIL`、`INCONCLUSIVE` 或 `NOT_APPLICABLE`。

依赖顺序以“前置能力 -> 消费方”表达。最底层是 `common`、Asset Reference contract 与相互独立的 auth/storage/cache/image/ai/mq/thirdparty/vector。File 消费 Asset Reference、Storage 和 MQ；Permission 消费 Auth-facing principal 与由端口提供的 File/Conversation/Task 证明；State 消费 Cache/Storage；DAG 消费 Image/AI/Thirdparty/Storage/State；Imagegen 消费 AI/Storage 和单图解析；Task 消费 DAG/Imagegen/State/Cache/MQ/Storage；Conversation 消费 Context/Session/Permission、DAG/Imagegen validated payload 和 Task public command；Vision 消费 File read model、Storage/Image/AI/MQ；Memory 消费 File resolver、AI embedding 和 Vector read API；Rubrics 消费 Task public outcome、Eval read contract、Storage/Image/AI。Vision 与 Memory 不互相依赖，Rubrics 不依赖 Vision、Memory、Agent、Loop、Tools 或 Hooks。

## Scope and Non-Goals

范围包括本次后端设计变更触达的生产代码、测试、数据库 migration、自动配置、ArchUnit 守护、应用组合根和必要的前端 API/状态对齐。范围内必须删除旧共享 confirmation/pending-plan 合同，统一 canonical references，替换 Proposal 决策链，完成结果资产发布、视觉事实、只读 Memory/Vector、Rubrics 四态评估和相关故障恢复。

范围不包括多租户、组织 RBAC、注册功能、视频处理、外部电商平台真实 API、业务计费、Rubrics 后训练、在线 memory 数据导入、兼容旧 pending-plan API、把 Rubrics 暴露为前端 Evaluation Center，或借重构进行无关代码风格整理。Lint 历史基线继续由 `lint-baseline-remediation-plan.md` 管理；本计划删除旧类时必须同步删除其精确 suppression，但不得生成新的宽泛 suppression。

## Plan of Work

### Milestone 0：审计实现差距、收敛文档并冻结 owner-defined contracts

本里程碑先建立可靠起点，不改变用户行为。执行者要保存工作树状态，完成当前活动 Lint 计划或明确把仍在修改的文件排除出本里程碑，随后为所有被删除的旧机制生成生产源码、测试、migration、配置和前端调用点清单。清单至少覆盖 confirmation token、challenge、pending plan、ATTACHMENT role、parallel package/image IDs、VisionService/general vision subagent、upload-time copy enrichment、Memory ingest/reinforce、Rubrics score/Promotion 和 task-result-as-asset 混用。

开始前必须阅读 `docs/design-docs/base/common.md`、`docs/design-docs/base/asset-references.md`、`docs/design-docs/base/contracts.md`、`docs/design-docs/design.md`、`CONTEXT-MAP.md`、`docs/adr/0003-terminal-tasks-use-derived-retries.md` 至 `docs/adr/0007-keep-unconfirmed-proposals-ephemeral.md`，以及 `docs/agents/domain.md`。执行域历史只参考 `docs/design-docs/exec-plans/completed/execution-domain-refactor-plan.md`；Rubrics 历史只参考 completed 计划的已完成事实，不采用其 Promotion 目标。

在 `pixflow-contracts`、各属主模块 public API 和 `pixflow-app` 组合根中先冻结最小接口形状，并为禁止依赖建立 ArchUnit 测试。此时不保留旧/新双实现。契约测试要先失败，证明当前代码仍暴露 confirmation、pending-plan 和 ATTACHMENT；随后各里程碑逐步使它们通过。完成标准是本文的 Interfaces and Dependencies 已被对应测试表达，任何执行者都能在不读取其他模块内部包的情况下实现后续阶段。

### Milestone 1：统一 Asset Reference、File 解析与消息 references

本里程碑建立全系统唯一素材身份。先在 `pixflow-contracts` 或等价最低层包中实现 canonical Asset Reference value/parser，只表达 PACKAGE、SKU、IMAGE kind 与不透明业务 ID；序列化只能由后端产生。`pixflow-module-file` 公开 resolver、expansion、inspection 和 generated-asset publication API，内部继续拥有 `asset_package`、`asset_image`、storage location、tombstone 和 lineage。PACKAGE/SKU 展开只在后端发生，精确重复只按相同 `referenceKey` 去重。

开始前必须阅读 `docs/design-docs/base/asset-references.md`、`docs/adr/0005-use-canonical-asset-reference-keys.md`、`docs/design-docs/module/file.md`、`pixflow-module-file/CONTEXT.md`、`docs/design-docs/infra/storage.md`、`docs/design-docs/module/commerce.md`、`docs/design-docs/harness/context.md`、`docs/design-docs/harness/session.md`、`docs/design-docs/module/conversation.md`、`pixflow-conversation/CONTEXT.md`、`docs/design-docs/frontend/api.md`、`docs/design-docs/frontend/chat.md`、`docs/design-docs/frontend/files.md` 和 `docs/design-docs/frontend/upload.md`。

把 USER message 改为在 metadata 中保存有序 `referenceKey + displayPathSnapshot`，删除 `MessageRole.ATTACHMENT`、附件消息构造器、attachment metadata 常量、AttachmentCollector 和前端 ATTACHMENT role。Context projection 必须保持 USER message 与 references 原子、顺序稳定，Session 成为唯一 durable message writer。历史素材删除后只保留渲染旧 mention 所需的最小 tombstone，不保留字节或把 display path 当身份。

完成标准是 File resolver 的 kind、ownership、availability、expansion、deduplication 和 tombstone contract tests 通过；消息落库再加载后 references 顺序和 snapshot 不变；API 拒绝 `packageId`、`attachments`、对象 key 和文件路径等并行身份；前端 Materials/Outputs/Chat 只回传后端给出的 key。

### Milestone 2：替换 Auth、Permission 与 Proposal 决策边界

本里程碑删除旧确认机制，形成“一次确认直接执行”的硬安全边界。`pixflow-infra-auth` 只允许配置项 `PIXFLOW_AUTH_ADMIN_USERNAME` 指定的管理员获得和继续使用会话；注册路由不存在并返回 404；login、refresh、`/me` 和每个 JWT 请求都重新检查当前资格。`pixflow-permission` 实现 deny-first 顺序：认证、管理员资格、runtime scope、Plan mode、tool/action 分类、conversation proof、Asset Reference proof、Proposal/task proof，缺少任何必要事实都拒绝。

开始前必须阅读 `docs/design-docs/infra/auth.md`、`pixflow-infra-auth/CONTEXT.md`、`docs/design-docs/infra/permission.md`、`docs/references/permission-architecture.md`、`docs/design-docs/harness/tools.md`、`docs/design-docs/harness/hooks.md`、`docs/design-docs/harness/loop.md`、`docs/design-docs/module/conversation.md`、`docs/design-docs/base/contracts.md` 和 `docs/adr/0007-keep-unconfirmed-proposals-ephemeral.md`。

Conversation 新增本地、短生命周期、线程安全的 ephemeral Proposal store，key 至少包含 conversationId 与 proposalId，value 包含 proposal kind、owner-defined validated payload、payload hash、reference snapshot、expiry 和 pending/confirming 状态。它不写 MySQL、Redis、transcript metadata 或审计表。进程丢失、刷新或过期可以丢失未确认 Proposal，这是 accepted tradeoff；用户重新让 Agent 生成即可。确认端点 body 为空，内部重新验证管理员、conversation、references、payload hash 和 pending CAS，然后用 proposalId 作为 Task business idempotency identity。成功绑定后删除临时 Proposal；重复确认通过 Task public idempotency lookup 返回已经绑定到同一 proposalId 的 taskId，而不是恢复 Proposal。重复 reject 成功且不写消息。

删除 `pixflow-contracts` confirmation/pending proposal 包、`RedisConfirmationTokenStore`、相关 metrics/config/tests、DAG `pending_plan` entity/mapper/service/migration 的目标基线、challenge endpoints、BULK level、前端 ConfirmationToken 类型和所有 token/challenge UI 状态。DAG 与 Imagegen 只返回 validated payload；`pixflow-app` 或 Conversation application adapter 负责把 ToolHandler 调用结果发布到 Conversation。

完成标准是 Plan mode、explore child、错误 kind/deleted reference、跨 conversation confirm、管理员不匹配全部 fail closed；一个合法 Proposal 只需一次确认；并发两次确认只创建一个 Task；进程内 store 丢失返回普通 Proposal expired/missing，而不是回查数据库或 Redis。

### Milestone 3：完成执行链和 Generated Image 发布闭环

本里程碑在已完成 Execution Domain 重构基础上做差距审计和最终对齐，而不是重写已经验证的 Canonical DAG、Work Unit、Execution Epoch、Derived Retry 与 Pixel Budget。先确认 `infra/image` 仍保持 probe-before-decode、JVM 全局加权预算、可重开来源和 raster ownership；State 只从 Task 提供的 MySQL SUCCESS read port 认 checkpoint；Redis Runtime Reference 只携带同 epoch 临时对象引用；DAG 不拥有线程池、MQ 或 process_result；Task 是 process_task/process_result 唯一写侧。

开始前必须阅读 `docs/adr/0001-work-unit-checkpoints.md`、`docs/adr/0002-redisson-lock-with-execution-fencing.md`、`docs/adr/0003-terminal-tasks-use-derived-retries.md`、`docs/adr/0006-publish-successful-image-results-as-assets.md`，以及 `docs/design-docs/infra/image.md`、`docs/design-docs/infra/storage.md`、`docs/design-docs/infra/cache.md`、`docs/design-docs/infra/mq.md`、`docs/design-docs/infra/ai.md`、`docs/design-docs/infra/thirdparty.md`、`docs/design-docs/harness/state.md`、`docs/design-docs/module/dag.md`、`docs/design-docs/module/task.md`、`docs/design-docs/module/imagegen.md`、`docs/design-docs/module/file.md`、`pixflow-infra-image/CONTEXT.md`、`pixflow-module-dag/CONTEXT.md`、`pixflow-module-task/CONTEXT.md`、`pixflow-state/CONTEXT.md`、`pixflow-module-imagegen/CONTEXT.md` 和 `pixflow-module-file/CONTEXT.md`。

把 Imagegen 收窄为一个 concrete IMAGE reference、一个 prompt、一个参数对象、一个 IMAGE_GEN task、一个 GENERATIVE Work Unit 和至多一个输出。PACKAGE/SKU 可以在 Proposal 生成前展开为多个独立 Proposal，但单个 Imagegen plan 不再接受 `source_image_ids`、`sourceImageIds` 或 batch。Task 确认入口接收 owner-defined immutable payload 和 proposalId，不重新引入随机 HTTP idempotency key。

所有输出先写 `taskId/unitKeyHash/runEpoch` 对应 candidate/TMP key。只有 owner thread 仍持锁、MySQL task epoch 相同、candidate 存在且 process_result 从 PENDING/RUNNING fenced 到 SUCCESS 后，才调用 `GeneratedAssetPublicationPort`。File 实现幂等 publication：分配新 imageId，把 candidate copy/move 到稳定 RESULTS 或 GENERATED asset key，记录 sourceTaskId、sourceResultId、sourceImageId、producer provider/model 和 lineage，返回 canonical IMAGE reference。Task 删除只删除 execution/activity 与未发布临时对象；File 删除 Generated Image 才删除稳定资产。

完成标准是旧 epoch 对象存在但 DB commit 被拒绝时不出现在查询或 Outputs；相同 result 重放只产生一个 Generated Image；源 imageId 永不复用；成功任务清除后 Generated Image 仍可查询和 mention；Derived Retry 只复制失败 Work Unit，来源终态不可变。

### Milestone 4：用 Product Visual Facts 替换通用 Vision 与上传期文案富化

本里程碑把视觉理解变为 Vision 自有的持久、可恢复事实源。File 在包解压终态后发布 package-ready domain event，由 app bridge 触发 Vision 的 package job，避免 File 编译依赖 Vision。Vision 按 `(packageId, skuId)` 创建 SKU work item，确定性选择至多两张图，冻结 visual input fingerprint；具体 IMAGE lookup 可创建 image-scoped work item。MySQL work item 持有 status、run_epoch、heartbeat、provider_attempt_count 和 structure_round_count，snapshot 表保存不可变事实与 operational metadata。

开始前必须阅读 `docs/adr/0004-use-durable-product-visual-facts.md`、`docs/adr/0005-use-canonical-asset-reference-keys.md`、`docs/design-docs/module/vision.md`、`pixflow-module-vision/CONTEXT.md`、`docs/design-docs/module/file.md`、`docs/design-docs/infra/ai.md`、`docs/design-docs/infra/image.md`、`docs/design-docs/infra/storage.md`、`docs/design-docs/infra/mq.md`、`docs/design-docs/infra/cache.md`、`docs/design-docs/harness/tools.md`、`docs/design-docs/agent/agent.md` 和 `docs/design-docs/module/imagegen.md`。

Vision 输入通过 Storage 的 reopenable source 和 Image 的全局 Pixel Budget 预处理，不新增 codec 或无界 byte array。每个实际 provider HTTP attempt 在调用前原子消耗 work-item 持久预算；transport retry 和结构修复共享最多三次实际 attempt，结构轮最多两轮。强制工具输出只能包含可观察事实、限制和冲突，不能包含 packageId、SKU、imageId、epoch、营销文案或 redraw instruction。提交 snapshot 时同时校验 owner thread lock 与相同 run_epoch；恢复扫描只看 stale heartbeat，不读取锁状态或 force unlock。

删除 `VisionService.analyze(question)`、`DefaultVisionService`、`ProductCopyExtractor`、CopyEnrichment consumer/message、通用 `agent(type=vision)` 和 VisionSubagentTool。唯一 Agent 工具是 `get_product_visual_facts(referenceKey)`；它返回当前 fingerprint 的 SKU/image snapshot，缺失时可在同一 work item、锁、epoch 和剩余预算下补偿，绝不把历史 snapshot 当 current。营销文案和 redraw YAML 由主 Agent 基于事实生成。

完成标准是相同输入指纹只产生一个 current snapshot；图片集合或 contract version 改变时旧 snapshot 只作历史；并发 consumer 与 lookup compensation 只有一个 owner；三次 attempt 预算不会被 infra/ai retry 乘法放大；零图不调用 provider；Agent schema 不再出现 general vision question 或 vision child。

### Milestone 5：收窄 Memory、Vector、Hooks 与 Agent

本里程碑删除所有在线业务 Memory 写回，只保留读取已有 user preference、SKU history 和 ACTIVE analysis insight。Memory public API 最终只有 `prepareContext(MemoryContextRequest)`；请求使用 canonical references 作为 recall signal，需要 SKU/image filter 时经 File resolver 得到，不接受平行 packageId/skuIds/imageIds。Memory 用 infra/ai 生成 query embedding，用 infra/vector 搜索管理员预先准备的 Qdrant ACTIVE 投影，与 MySQL FULLTEXT 做确定性 RRF 融合并执行 token budget；读取不增加访问次数、不 reinforce、不改变 lifecycle。

开始前必须阅读 `docs/design-docs/module/memory.md`、`docs/design-docs/infra/vector.md`、`docs/design-docs/agent/agent.md`、`docs/design-docs/harness/hooks.md`、`docs/design-docs/harness/context.md`、`docs/design-docs/harness/eval.md`、`docs/design-docs/module/file.md`、`docs/design-docs/module/vision.md` 和 `docs/design-docs/design.md` 第七章。还要阅读 `docs/references/prompt-architecture.md`、`docs/references/hook-architecture.md` 与 `docs/references/context-architecture.md`，避免把业务 Memory 与 conversation-scoped Session Memory 混淆。

删除 `MemoryIngestService`、`InsightIngestService`、Noop ingest、reinforce/upsert/consolidation API、AssistantMemoryIngestionHook、Task/Rubrics write hooks、online Qdrant upsert/delete/rebuild compensation 和 Agent-visible recall/write tool。Qdrant collection 缺失或维度不匹配时 Vector 组件 fail closed，Memory 可退化到 FULLTEXT 或空 section，不阻断 Conversation。Product Visual Facts 不进入 Qdrant 或 Memory；Agent 在外观相关回答前显式调用 Vision lookup。

完成标准是 architecture tests 证明 Agent、Conversation、Task、Hooks、Rubrics 无法获得 Memory/Vector 写端口；所有 recall 成功和降级路径无数据库或向量写；相同输入得到稳定排序；Session Memory 仍只属于 Context/Agent 压缩，不被提升为业务事实。

### Milestone 6：完成 Rubrics 离线四态评估

本里程碑以最新 Rubrics 设计为准，保留已完成 v2 事实模型和 judge 基础，审计并补齐未完成部分。Rubrics 对 IMAGE_RESULT、COPY_RESULT、TASK_DECISION 分别构建 typed Evaluation Subject；template 是人工批准、版本化、绑定单一 subject type 的 YAML；Criterion 是原子 Hard Rule 或 Principle；Evidence Pack ID、type、source ref 和 hash 由系统生成；每个 LLM criterion 默认三次独立 rollout，只有严格多数 PASS 或 FAIL 才形成对应 verdict，其余为 INCONCLUSIVE。

开始前必须阅读 `docs/design-docs/module/rubrics.md`、`pixflow-module-rubrics/CONTEXT.md`、`pixflow-module-rubrics/docs/adr/0001-use-criterion-verdicts-instead-of-quality-scores.md`、`docs/design-docs/harness/eval.md`、`docs/design-docs/module/task.md`、`docs/design-docs/infra/ai.md`、`docs/design-docs/infra/storage.md`、`docs/design-docs/infra/image.md` 和 `docs/design-docs/design.md` 第十一章。`docs/design-docs/exec-plans/completed/rubrics-criterion-verdict-refactor-plan.md` 只用于识别已落地代码和测试；其中 Promotion、Memory dependency 和旧前端 route 目标明确不执行。

先完成 IMAGE_RESULT 的 task producer provider/model provenance、并发 resume MySQL 集成测试和 run/API/automation ArchUnit。然后实现 immutable Evaluation Dataset、gold labels、calibration、同 dataset paired regression、baseline 和 Rubrics-owned alert。Copy Result 在 Image Result 通过门槛后实现；Task Decision 最后实现，因为它依赖稳定 proposal/decision revision、DAG snapshot 和 criterion-specific eval trace。trace 缺失或过期必须标 non-replayable 或 INCONCLUSIVE，不能变成 FAIL。

删除仍存在的 scalar score 生产路径、confidence-to-score、自动 feedback、HookCallback 和任何 Promotion entity/service/table/API。Rubrics 直接异步订阅 Task public completed event，listener 只把 command 交给 Rubrics 自有有界 executor，Rubrics 故障不能改变 Task terminal state。前端不新增 Evaluation Center。

完成标准是四态、nullable passRate/coverage、Quality Gate、非法 evidence、judge disagreement、selfJudged provenance、dataset pairing 和 non-replayable 行为都有测试；Rubrics 对 Memory/Vision/File/Agent/Loop/Tools/Hooks 零编译依赖，对 Task 只消费 public immutable outcome/event。

### Milestone 7：组合根、前端对齐、清理与端到端验收

本里程碑把已经独立验证的模块在 `pixflow-app` 组合根接线。App 提供 AI quota/concurrency 到 Cache 的 adapters、Permission proof ports、DAG/Imagegen Proposal ToolHandlers、File package-ready 到 Vision 的 bridge、Task GeneratedAssetPublicationPort 到 File 的 adapter、ToolTraceSink 到 Eval 的 adapter，以及 HTTP/SSE/STOMP controllers。必需生产 bean 缺失时启动失败；测试用 fake 必须显式注入，不能靠 `@ConditionalOnBean` 静默少装能力。

开始前必须重读 `docs/design-docs/design.md`、`base/contracts.md`、`frontend/README.md`、`frontend/product.md`、`frontend/api.md`、`frontend/stores-runtime.md`、`frontend/chat.md`、`frontend/files.md`、`frontend/tasks.md`、`frontend/upload.md`、`frontend/transport-api.md`、`frontend/shell-routing-auth.md` 和所有本计划触达模块文档的 Contracts/Verification 段。若某个文件在当前仓库已删除或变成兼容入口，以 `docs/design-docs/index.md` 当时列出的权威文件为准并记录发现。

前端移除 attachments、challenge/token、bulk Imagegen、task-output-as-file 和 Rubrics routes；Materials/Outputs 使用 canonical references，Proposal cards 只存在当前 SPA runtime，确认/reject body 为空，Activity 与 Outputs 生命周期分离。注册入口仅显示未开放，后端注册路由仍为 404。WebSocket/SSE 不泄漏 raw tool 参数、reference ownership facts、provider prompt 或 permission details。

最后删除所有旧 Java/TS/Vue 类型、auto-configuration beans、properties、metrics、migration 目标引用和精确 Checkstyle suppression。运行 architecture scans、所有触达模块测试、前端四项验证、后端严格 verify、完整 verify 和真实依赖故障注入。更新本计划 living sections、Context Map、模块 CONTEXT/ADR 和设计文档中实际发生的必要偏差；completed 历史计划不重写。

## Parallel Work Strategy

Milestone 0 的接口和架构测试冻结后，可以并行推进以下工作流，但同一文件只能有一个所有者。Auth、Storage、Cache、Image、AI、MQ、Thirdparty、Vector 都只依赖 Common，可各自独立。Context/Session references 与 File resolver 可并行，但集成测试等待双方。DAG 与 Vision 共同消费 Image/AI/Storage，却互不依赖，可以并行。Vision 与 Memory 没有依赖，可以并行；Agent 的最终接线等待两者 public API。Rubrics 领域核心、dataset 和 report 可与 Vision/Memory 并行，但 IMAGE_RESULT adapter 等待 Task public outcome，TASK_DECISION adapter 等待 Proposal/DAG/trace 合同稳定。

不可并行的关键链为 Asset Reference -> File resolver -> Permission/Proposal validation -> Conversation confirm -> Task；以及 Image/Storage/State/DAG/Imagegen -> Task -> GeneratedAssetPublicationPort -> File。任何并行分支在合并前必须运行自己的模块测试和 architecture test，合并后再串行运行 Maven reactor。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 执行。每次开始或恢复工作先运行：

    git status --short
    rg --files docs/design-docs/exec-plans | rg -v "completed[\\/]"
    git diff -- docs/design-docs config/checkstyle/suppressions.xml

不得清理用户工作。若 `lint-baseline-remediation-plan.md` 仍在进行，先阅读其 Progress，避免同时修改其当前模块；需要删除旧文件时同步删除该文件的 suppression，并按 Lint 计划运行严格门禁。

Milestone 0 建立旧路径清单：

    rg -n "ConfirmationToken|PendingPlan|pending_plan|ATTACHMENT|source_image_ids|sourceImageIds" --glob "*.java" --glob "*.sql" --glob "*.xml" --glob "*.ts" --glob "*.vue" .
    rg -n "VisionService|ProductCopyExtractor|agent\\(type=vision\\)|VisionSubagentTool" --glob "*.java" .
    rg -n "MemoryIngestService|ingestAsync|reinforce\\(|rubrics_promotion|PromotionService|overallScore|Confidence" --glob "*.java" --glob "*.sql" --glob "*.ts" .
    mvn -DskipTests verify

把命中按 owner module 写入本计划 Artifacts and Notes；不能把测试或 migration 命中简单视为生产缺陷，必须区分“待删除生产路径”“待迁移测试”“只读历史 migration”。

Milestone 1 完成后运行：

    mvn -pl pixflow-contracts,pixflow-module-file,pixflow-context,pixflow-session,pixflow-conversation -am test
    mvn -pl pixflow-contracts,pixflow-module-file,pixflow-context,pixflow-session,pixflow-conversation -am -DskipTests verify
    rg -n "MessageRole.ATTACHMENT|ATTACHMENT_TYPE|ATTACHMENT_REF|attached_package_id|object://" --glob "*.java" --glob "*.ts" --glob "*.vue" .

最后一条在生产源码预期无输出。若 migration 或只读兼容 fixture 必须保留旧列名，在本计划记录路径和只读原因。

Milestone 2 完成后运行：

    mvn -pl pixflow-infra-auth,pixflow-permission,pixflow-hooks,pixflow-tools,pixflow-loop,pixflow-conversation,pixflow-module-dag,pixflow-module-imagegen,pixflow-module-task,pixflow-app -am test
    mvn -pl pixflow-infra-auth,pixflow-permission,pixflow-conversation,pixflow-app -am -DskipTests verify
    rg -n "ConfirmationToken|ConfirmationLevel|challenge|PendingPlan|pending_plan|PendingPlanPort" --glob "*.java" --glob "*.ts" --glob "*.vue" --glob "*.xml" .

生产源码和前端命中预期为零。数据库 migration 历史文件若受 Flyway checksum 约束不得就地修改；开发基线需要删除旧表时用新 migration 或重写尚未发布的开发 V1，并在 Decision Log 记录选择。

Milestone 3 完成后运行：

    mvn -pl pixflow-infra-image,pixflow-infra-storage,pixflow-infra-cache,pixflow-state,pixflow-module-dag,pixflow-module-imagegen,pixflow-module-task,pixflow-module-file,pixflow-app -am test
    mvn -pl pixflow-module-task,pixflow-module-file,pixflow-app -am -DskipTests verify
    rg -n "source_image_ids|sourceImageIds|max-source-images|task.*delete.*asset" --glob "*.java" --glob "*.ts" --glob "*.vue" .

使用 Testcontainers profile 或仓库当时记录的 Windows Docker profile运行 Redis disconnect、lost lock、MySQL higher-epoch fencing、MinIO orphan candidate 和 publication idempotency 测试。所有容器测试必须报告零 skipped 才能记为真实依赖通过。

Milestone 4 完成后运行：

    mvn -pl pixflow-module-file,pixflow-infra-ai,pixflow-infra-image,pixflow-infra-storage,pixflow-infra-mq,pixflow-infra-cache,pixflow-module-vision,pixflow-agent,pixflow-app -am test
    mvn -pl pixflow-module-vision,pixflow-agent,pixflow-app -am -DskipTests verify
    rg -n "VisionService|DefaultVisionService|ProductCopyExtractor|CopyEnrichment|VisionSubagentTool|SubagentType.VISION" --glob "*.java" .

最后一条生产源码预期无输出。provider 行为必须由 fake client 或本地 fake HTTP server 测试，自动测试不得调用真实付费 provider。

Milestone 5 完成后运行：

    mvn -pl pixflow-infra-vector,pixflow-module-memory,pixflow-hooks,pixflow-context,pixflow-eval,pixflow-agent,pixflow-app -am test
    mvn -pl pixflow-infra-vector,pixflow-module-memory,pixflow-agent,pixflow-app -am -DskipTests verify
    rg -n "MemoryIngestService|InsightIngestService|ingestAsync|reinforce\\(|upsert|deleteByFilter|AssistantMemoryIngestionHook" pixflow-module-memory pixflow-infra-vector pixflow-agent pixflow-hooks

搜索不得命中生产写 API；Qdrant adapter 内部只读 search/get/verify 名称不应误判为业务写。

Milestone 6 完成后运行：

    mvn -pl pixflow-infra-ai,pixflow-infra-image,pixflow-infra-storage,pixflow-eval,pixflow-module-task,pixflow-module-rubrics,pixflow-app -am test
    mvn -pl pixflow-module-rubrics,pixflow-app -am -DskipTests verify
    rg -n "PromotionService|ReviewedInsightService|rubrics_promotion|MemoryIngestService|overallScore|imageScore|copyScore|decisionScore|Confidence" pixflow-module-rubrics pixflow-module-memory

生产源码命中预期为零；V1 migration 中只读历史列可保留，但新 v2 写路径和 API 不得使用。

Milestone 7 最终运行：

    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build
    mvn -DskipTests verify
    mvn verify
    git diff --check
    git status --short

完整 `mvn verify` 若因本机 Docker 不可用失败，必须记录 `docker info` 或 pipe 证据、首个失败测试和栈帧；同时分别证明非 Docker 触达模块测试与 `mvn -DskipTests verify` 成功。环境阻断不能被记为测试通过，也不能成为删除或 skip 测试的理由。

## Validation and Acceptance

Asset Reference 验收要求后端是唯一 key producer；PACKAGE、SKU、IMAGE kind 解析稳定；wrong-kind、deleted、cross-owner 和 object-key 输入被拒绝；PACKAGE/SKU expansion 在服务端确定性去重；历史 message 的 display snapshot 不随 rename 改写。

Proposal 验收要求一个合法 Proposal 展示前已经完成 schema、reference、ownership、readiness、count、payload hash 和 domain fact validation。用户只点击一次确认；并发或网络重放返回同一 taskId；reject 不写新消息；Redis/MySQL 中不存在 Proposal、challenge 或 confirmation token；进程丢失未确认 Proposal 时用户可重新生成。

执行验收要求相同 Canonical DAG 总能编译出相同 branch 和 Work Unit identity；Pixel Budget 在 decode 前取得并在所有异常路径释放；恢复只跳过 MySQL SUCCESS；Redis miss 或 epoch mismatch 只导致重算；旧 worker 无法覆盖 SUCCESS 或新 epoch；Derived Retry 创建新 task 且来源不变。

资产发布验收要求 deterministic 与 Imagegen 成功结果都获得新 imageId、stable object key、lineage 和 canonical IMAGE reference；同 result 重放不重复发布；清除 Task/Activity 后 Outputs 仍存在；删除 Generated Image 才清理稳定对象；失败、取消、fenced candidate 从不出现在 Outputs。

Vision 验收要求 package job 的一个 SKU 失败不阻塞其他 SKU；相同 fingerprint 恢复不重复调用；三次 provider attempt 是跨 transport/structure 的总预算；历史 fingerprint 不作为 current 返回；IMAGE lookup 返回 image snapshot 与可用 SKU context；事实不包含营销文案、产品推断或 redraw instruction。

Memory/Vector 验收要求所有 recall 路径无写副作用；Qdrant 失败可降级到 FULLTEXT；MySQL 失败返回部分或空 context 而不阻断对话；Agent prompt 能同时使用已有 Memory Context 和显式 Product Visual Facts，但二者不互相写入。

Rubrics 验收要求 Hard Rule 任一 FAIL 使 Quality Gate FAILED，无 FAIL 但有 INCONCLUSIVE 使 UNKNOWN，全部适用 Hard Rule PASS 使 PASSED；Principle passRate 和 coverage 在零分母时为 null；非法 evidence、parser error、provider failure 和无多数意见不伪装成 FAIL；formal regression 只比较同 dataset version；任何 run、alert 或人工 review 都不写 Memory。

端到端验收要求配置管理员登录成功、其他历史账号无法获得或续期会话、注册返回 404；上传素材后 Chat 能 mention canonical reference；一张图的 Imagegen Proposal 单击一次创建一个任务和至多一个 Generated Image；任务删除后输出仍可 mention；前端不存在 attachment、challenge/token、bulk confirm 或 Rubrics 页面状态。

## Idempotence and Recovery

所有审计、搜索、编译和测试命令可以重复运行。代码迁移以模块公开契约和数据库事实源为恢复点；任何里程碑中途停止时，必须在 Progress 把已完成和剩余部分拆开记录，不能只写“进行中”。

不得执行 `git reset --hard`、`git checkout --`、整仓 restore 或删除用户工作。开始修改文件前检查 `git status --short`；遇到现有修改时在其上做最小补丁或延后。删除旧类时一并更新调用方、测试、auto-configuration 和精确 Checkstyle suppression，避免留下只编译于本地 Maven 缓存的半迁移状态。

数据库迁移策略必须在 Milestone 0 根据部署事实确定。若数据库仍是可重建的开发库，可以一次性改目标 baseline 并重建；若 migration 已在共享环境执行，保留旧文件 checksum，用新增 migration 删除或废弃旧表/列。任何情况下都不在生产代码增加 legacy reader、nullable fallback、双写或旧 API wrapper。执行破坏性数据库操作前必须确认环境并备份有价值数据。

Proposal 丢失按设计恢复为重新生成，不从 transcript、Redis 或 pending_plan 还原。Task MQ 发布失败保持可扫描的 PENDING 或按状态机落明确失败；重新入队依靠 business idempotency 和 checkpoint。Vision crash 依靠 work item status、heartbeat、epoch 和持久 attempt budget恢复。Rubrics item commit 前失败可重跑，commit 后由唯一键和 terminal checkpoint 跳过；底层 evidence 丢失时标 non-replayable，不伪造。

## Artifacts and Notes

目标依赖主链：

    common / Asset Reference
      -> File resolver
      -> Permission and Proposal validation
      -> Conversation confirm
      -> Task
      -> GeneratedAssetPublicationPort
      -> File Generated Image

执行能力链：

    Image + Storage + Cache/State + AI/Thirdparty
      -> DAG / Imagegen
      -> Task owner scheduler and fenced commit
      -> File publication

可并行分支：

    DAG || Vision
    Vision || Memory
    Memory || Rubrics
    Vision || Rubrics
    Context/Session || Image/Execution foundations

最终旧路径清理搜索至少覆盖：

    ConfirmationToken / ConfirmationLevel / challenge
    PendingPlan / PendingPlanPort / pending_plan
    MessageRole.ATTACHMENT / attachment metadata
    source_image_ids / sourceImageIds
    VisionService / ProductCopyExtractor / VisionSubagentTool
    MemoryIngestService / ingestAsync / reinforce / vector writes
    scalar Rubrics scores / Confidence / Promotion

## Interfaces and Dependencies

Asset Reference 的最低层公开形状应等价于：

    public enum AssetReferenceKind { PACKAGE, SKU, IMAGE }

    public record AssetReferenceKey(
        AssetReferenceKind kind,
        String packageId,
        String skuId,
        String imageId
    ) {}

    public interface AssetReferenceParser {
        AssetReferenceKey parse(String referenceKey);
        String serialize(AssetReferenceKey reference);
    }

实际实现可以用 sealed records 代替 nullable components，但必须使非法 kind/字段组合不可构造。File public API 提供 typed resolve、expand 和 inspect；对象存储位置不能穿过该 API。

Permission 不直接依赖 File、Conversation、Task 的 mapper/entity，而拥有窄 proof ports：

    public interface AssetAuthorizationFactsPort {
        AssetAuthorizationFacts resolve(String referenceKey, AssetAccessMode mode);
    }

    public interface ConversationAuthorizationFactsPort {
        ConversationAuthorizationFacts prove(String conversationId, AuthenticatedPrincipal principal);
    }

    public interface TaskAuthorizationFactsPort {
        TaskAuthorizationFacts prove(String taskId, AuthenticatedPrincipal principal);
    }

Conversation 拥有 Proposal，不把以下类型放进 `pixflow-contracts`：

    public interface ProposalService {
        ProposalView publish(PublishProposalCommand command);
        ProposalDecisionResult confirm(String conversationId, String proposalId,
                                       AuthenticatedPrincipal principal);
        void reject(String conversationId, String proposalId,
                    AuthenticatedPrincipal principal);
    }

DAG 与 Imagegen 分别暴露 `ValidatedImagePlan` 和 `ValidatedRedrawRequest` 或语义等价的 immutable public records。它们不包含 Proposal status、expiry、task binding 或 persistence。App adapter 把 validated payload 交给 `ProposalService.publish`。

Task-owned publication seam 应等价于：

    public interface GeneratedAssetPublicationPort {
        PublishedAsset publish(GeneratedAssetCandidate candidate);
    }

`GeneratedAssetCandidate` 必须包含 taskId、resultId、unitKey、runEpoch、candidate object、source image identity、media metadata 和 producer identity；File 返回新 imageId、stable object、lineage 与 canonical reference。Task 不 import File persistence package。

Vision public API 最终只暴露 job trigger/readiness 所需的 application API 与：

    public interface ProductVisualFactsLookup {
        ProductVisualFactsView get(String referenceKey);
    }

Agent-visible schema 只含 `referenceKey`。Provider request 的强制事实工具、attempt reservation、epoch 和 snapshot persistence 都是 Vision 内部实现。

Memory public API 最终只有：

    public interface MemoryService {
        MemoryContext prepareContext(MemoryContextRequest request);
    }

Vector runtime API最终只有 collection verification、search 和 optional get，不暴露 upsert/delete。Rubrics 没有 Memory dependency。

Rubrics 最终以一个深模块 service 隐藏 template、Evidence Pack、rollout、majority、persistence 和 summary：

    public interface RubricsEvaluationService {
        EvaluationRunView start(EvaluationRunCommand command);
        EvaluationRunView resume(String runId);
        EvaluationRunView get(String runId);
    }

Rubrics 只依赖 Task public outcome/event、Eval read contract、infra/ai、infra/storage、infra/image 和自己的 persistence。它不依赖 Vision、File 内部实现、Memory、Agent、Loop、Tools 或 Hooks。

## Revision Notes

2026-07-17 / Codex: 更新 Milestone 2 交接：Auth、Permission、ephemeral Proposal、direct confirm/reject 与 Task 幂等 replay 已落地并完成定向验证；明确 canonical Asset Reference 工具合同仍受 Milestone 1 前置阻塞，因此总里程碑保持未完成。

2026-07-17 / Codex: 创建本后端总重构计划。计划基于提交 `0faa171` 的最新目标设计和当前工作树，按契约冻结、Asset Reference、Auth/Permission/Proposal、执行与资产发布、Product Visual Facts、只读 Memory/Vector、Rubrics 离线评估、组合根与端到端验收的顺序组织。明确 completed 旧计划只作为历史证据，解决旧 Rubrics Promotion 与最新“无 Memory 写回”设计冲突；同时记录活动 Lint 计划的文件所有权，要求 Maven reactor 串行验证并保护用户现有修改。
