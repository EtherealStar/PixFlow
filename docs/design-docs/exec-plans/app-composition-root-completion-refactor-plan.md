# 彻底完成 pixflow-app 组合根、传输边界与全局 Activity

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`，并作为 `docs/design-docs/exec-plans/backend-architecture-alignment-refactor-plan.md` Milestone 7 中 App 子范围的专项执行计划。执行者只依赖当前工作树和本文，就应能把 `pixflow-app` 从若干临时 controller、内部 mapper 调用和跨模块拼接类的集合，重构为可启动、可恢复、可观测且有严格边界守护的 Spring Boot 组合根。每个停止点都必须更新本文；改变接口归属、数据库基线、传输契约、必需 Bean 清单、里程碑顺序或验收命令时，必须同步更新本文所有 living sections 和文末 `Revision Notes`。

## Purpose / Big Picture

完成后，`pixflow-app` 是 PixFlow 唯一可部署进程和面向 Web 的传输边界。它显式装配各深模块的公开 API，通过窄端口连接 File、Task、Conversation、Vision、Permission、Eval、AI、Cache、MQ 与 Storage；任何业务模块都不依赖 app，app 也不再 import 其他模块的 `internal`、`infra.persistence`、`runtime`、mapper、entity 或领域内部状态。业务规则仍由所属模块执行，app 只做认证后的请求适配、跨上下文端口适配、事件投影和进程级配置。

管理员可以启动一个完整应用，登录后通过权威 REST/SSE/STOMP 合同完成素材上传、对话、逐项 Proposal 确认、任务执行、全局 Activity 跟踪、Outputs 浏览和 Product Visual Facts 管理。冷启动或 STOMP 重连后，`GET /api/activities` 能恢复管理员范围内的当前活动；事件丢失由权威模块快照对账修复。缺失生产必需 Bean、数据库未迁移、错误 profile 或外部依赖不可用时，应用以明确诊断启动失败或 readiness 为 DOWN，不会靠 `@ConditionalOnBean`、`ObjectProvider` 或 Noop 实现静默少装核心能力。

这是重构，不做旧机制兼容。删除旧 controller、旧 URL、旧 DTO、旧 Bean 名称、旧配置键、内部包直连和过渡 wrapper；不保留双路由、双写、nullable fallback、legacy reader 或隐藏开关。项目处于开发阶段，数据库按目标基线重建；若本机存在有价值数据，执行者先导出备份，再清空开发库并按新基线初始化，不为旧 schema 编写生产兼容代码。

## Progress

- [x] (2026-07-19) 阅读 `AGENTS.md`、`PLANS.md`、全部活动执行计划、`docs/design-docs/index.md`、总体设计、Context Map、适用 ADR、Lint 说明、App 源码/测试和前端权威 API 文档。
- [x] (2026-07-19) 完成现状审计并创建本专项计划；确认 App 内部包依赖、Activity 空缺、Noop trace、隐式装配和启动基线是主要缺口。
- [ ] Milestone 0：冻结 App 职责、公开端口、传输清单、必需 Bean 清单和失败测试。
- [ ] Milestone 1：建立可重复的目标数据库基线、profile/config 边界和真实进程启动契约。
- [ ] Milestone 2：补齐 owner-defined public API，删除 App 对所有模块内部实现的依赖。
- [ ] Milestone 3：把前端 HTTP/SSE/STOMP 入口收敛到 App，并删除模块内旧 Web 入口。
- [ ] Milestone 4：实现 App-owned 全局 Activity 持久投影、可靠增量和命令路由。
- [ ] Milestone 5：完成 Proposal、Permission、AI、Vision、Task/File、Eval 的显式组合接线和 fail-fast 守护。
- [ ] Milestone 6：删除旧代码，完成 lint-first 验证、真实依赖故障矩阵和端到端验收。
- [x] (2026-07-20) 完成第一批 App 后端边界迁移：新增 Task-owned `TaskAuthorizationFactsQuery`，权限证明与进度桥不再直连 `ProcessTaskMapper`；File 公开内容读取能力扩展到 current-original、复合身份、内容哈希、受控 stream/presign，File→Vision 改走公开 API；Vision trigger 的 RocketMQ publisher 移回 Vision owner；删除旧 App publisher。
- [x] (2026-07-20) 完成第一批传输归属迁移：Conversation REST/SSE、File HTTP controller 迁入 `pixflow-app`，Commerce controller 删除；App controller package 收敛到 `com.pixflow.app.web.*`；新增 `/turns/stop` 与 `USER_STOPPED`；删除旧 custom-download `type/imageId/resultId` wire model，统一使用 canonical IMAGE `referenceKey`。未修改前端。
- [x] (2026-07-20) 完成第一批内容能力与配置守护：`PublishedAssetReader` 不再暴露 `ObjectLocation`，Task download/bundle 与 Rubrics evidence 改用受控 `open()/presign()`；删除基础配置默认激活 `dev`；加入 App ArchUnit 边界测试；File/Commerce 移除 Web starter，并迁移相应 controller 测试。
- [x] (2026-07-20) 闭环第一批架构红灯：删除 File `runtime/AssetImageQuery` 与 `AssetImageDescriptor`，由 File-owned `AssetExecutionSnapshotQuery` 提供可信执行快照；App 生产源码不再 import 其他模块的 `internal/infra/persistence/runtime`；Vision controller 与测试物理移动到 `app.web.vision`；clean 重建后 App ArchUnit 3 项和本批公开 seam 23 项测试全部通过。
- [x] (2026-07-20) 闭环 canonical source/content 边界：删除临时 File 执行快照和旧 runtime image query/descriptor；Task/DAG/Imagegen 只冻结 canonical `referenceKey` 与内容元数据，App 通过 File `AssetContentReader` 显式装配读取能力，执行载荷不再携带 `ObjectLocation`。
- [x] (2026-07-20) 闭环剩余传输归属：Conversation SSE session/sink/heartbeat/metrics 物理迁入 App，Conversation 删除 Spring Web 依赖；Auth、Download、Task controller/test 物理迁到声明 package；ArchUnit 增加业务模块不得依赖 Spring Web/SSE/STOMP 的守护。
- [x] (2026-07-20) 删除生产 Noop tool trace：`RegistryToolExecutor` 按每次执行的 `ToolExecutionContext.traceSink()` 记录，Agent Loop 注入真实 `LoopToolTraceSink`；删除 `NoopToolTraceSink` 及默认 Bean，不保留兼容构造器。
- [x] (2026-07-20) 建立 Activity 核心 seam：App 定义 typed `ActivityView`、source revision、UPSERT/REMOVE frame、管理员过滤分页与 cursor；重复/乱序 revision 不回退，source owner 不可变；新增 JDBC projection/outbox repository 与 `db/app-activity/V1__create_activity_projection.sql`，outbox sequence 在 STOMP 成功后才标记 delivered。
- [x] (2026-07-20) 新增 `/api/activities` snapshot/detail/cancel/clear controller 合同和单一 `/user/queue/activity` dispatcher；删除旧 App generic progress notifier、Conversation topic bridge、Task progress bridge 及 Conversation Web transport fallback。9 项 Activity/架构定向测试通过。
- [x] (2026-07-20) 闭环 Activity owner source：Task/File 提供分页 current snapshot，App 按 owner 快照对账并删除 stale source；Upload 与 Package 使用不同 source kind/id；cancel/clear 命令回到 owner，Task cleanup 不删除 File-owned Generated Image。Activity 定向测试与 App 全部 56 项测试通过。
- [x] (2026-07-20) 建立 File、Task、App Activity 三个目标 V1 baseline；删除 File/Task 旧增量 migration 与生产 `schema.sql` 双源，App 为三个 location 使用独立 history table，并禁止 baseline、out-of-order 和 clean。File/Task/App Checkstyle 均为 0，未运行全仓测试。
- [x] (2026-07-20) 删除 File 解压链路的 Noop `ProgressNotifier` 与生产 `@ConditionalOnBean` 静默缺装；上传缓存、MQ 发布、destination 注册和 listener container 改为构造依赖 fail fast，Activity 由 owner snapshot 恢复进度。
- [x] (2026-07-20) Proposal tools 删除 handler 同时充当 `@Component`/Bean factory 的隐式装配，改由 `ProposalToolConfiguration` 显式组装；两个 descriptor 使用真实输入校验器，不再调用 `ToolInputValidator.noop()`。
- [x] (2026-07-20) 按前端目标设计补全 `frontend/api.md` 的 envelope、公开 DTO、Materials 图片详情、Outputs、Product Visual Analysis、Activity snapshot/frame/commands 与 removed-routes 清单；统一 Cancel/Retry failed/Clear 只经 Activity Command 进入前端合同。本项只冻结目标文档，当前旧 Task 路由和 Web 调用仍由 Milestone 3/父计划 Milestone 7 删除。
- [ ] (2026-07-20) 下一批仍未闭环：其余持久 owner 的独立目标 baseline、完整 App wiring contract/negative context、MySQL 8.4 空库启动、真实依赖故障矩阵和端到端验收尚未完成。Milestone 0、1、2、3、4、5、6 均保持未完成。

## Surprises & Discoveries

- Observation: 原 `frontend/api.md` 已写出新的 Outputs 与 Activity 路由，但没有完整 wire DTO，并同时保留 direct Task retry command，和“Activity 是唯一前端命令面”“standalone Task API 已删除”互相冲突。
  Evidence: 旧文档同时包含 `POST /api/tasks/{taskId}/retry-failed`、Activity cancel/clear，以及 removed contracts 中的 standalone Task API；`frontend/tasks.md` 则规定 Activity runtime 拥有命令，本文 Milestone 4 已要求 `POST /api/activities/{activityId}/retry-failed`。

- Observation: File→Task/DAG/Imagegen 不能通过给 runtime 类型改名来消除存储泄漏，最终边界必须冻结 canonical reference，并在实际读取点调用 owner content capability。
  Evidence: `AssetExecutionSnapshot*`、`AssetImageQuery`、`AssetImageDescriptor` 已删除；`TaskAssetReader.GenerativeSource`、DAG `ImageDescriptor` 和 `GenerativeUnitSpec` 只携带 `referenceKey`/业务元数据，App 以 `AssetContentReader` 装配 source readers。

- Observation: 第一批记录声称 `FileVisualAssetReaderTest` 的调用错误已经修正，但完整 reactor 仍发现测试调用不存在的 `ReopenableImageSource.open()`。
  Evidence: 2026-07-20 执行 30 模块 `mvn -Plinters-audit ... -DskipTests verify` 时，前 29 个模块成功，App 在该测试的 testCompile 阶段失败；实际公开方法为 `openStream()`，现已按接口修正。

- Observation: 权威设计要求全局 Activity REST 快照和 `/user/queue/activity` 增量，当前生产源码没有 Activity controller、projection、repository 或持久事件实现。
  Evidence: 全仓生产 Java 搜索 `activityId|/api/activities|/user/queue/activity` 只命中 Task 查询实现中的一条注释。

- Observation: Tool trace 的正确归属不是进程级默认 sink，而是 turn-scoped `ToolExecutionContext`；全局 Noop 会吞掉真实 Agent Loop 已提供的 trace。
  Evidence: `NoopToolTraceSink` 与默认 Bean 已删除；`RegistryToolExecutor` 每次调用使用 context sink，`LoopToolTraceSinkTest`、`TraceFanoutTest` 与 `RegistryToolExecutorCancellationTest` 定向通过。

- Observation: 当前 `application.yml` 无条件激活 `dev`，`application-dev.yml` 包含本地数据库和基础设施凭据。这会让生产启动意外继承开发连接信息。
  Evidence: `spring.profiles.active: dev` 位于 App 基础配置，数据库、Redis、MinIO、RocketMQ 和 Qdrant 地址位于 `application-dev.yml`。

- Observation: 各模块资源中存在重复版本号的 migration，App POM 未引入 Flyway，也没有一个启动测试证明空 MySQL 能由可部署进程迁移到目标 schema。
  Evidence: Auth、Conversation、Session、Task、Vision、Rubrics 和 Agent 均有 `V1__*.sql`；File 从 V2 开始且另有 `schema.sql`；全仓 POM 没有 Flyway 配置。

- Observation: 当前 App controller 并非完整 Web 边界。Conversation 与 File controller 位于业务模块，Commerce 还暴露了不在 `frontend/api.md` 中的 HTTP 面；App 仅有 Auth、Task、Download 和 Vision controller。
  Evidence: `@RestController` 生产扫描命中 App、Conversation、File 和 Commerce 四个模块，且没有 Activity controller。

- Observation: App 的架构守护主要是源码路径字符串扫描，只覆盖 Proposal handler 和 Custom Download，无法阻止新增内部包依赖、mapper 直连或模块反向依赖 App。
  Evidence: `ProposalOwnershipArchitectureTest` 和 `CustomDownloadTaskBoundaryTest` 使用 `Files.walk/readAllLines`，当前 App 测试本身仍 import Task mapper 与 File runtime 类型。

- Observation: 活动 Lint 计划仍拥有 `pixflow-app` 的 Checkstyle suppression 清理，当前 suppression 文件也有 App 条目。
  Evidence: `config/checkstyle/suppressions.xml` 包含 App AI、Auth、Config、Download 和 Task 文件；`lint-baseline-remediation-plan.md` Milestone 5 尚未完成。

- Observation: Maven `-rf :pixflow-app` 不能作为本轮修复后的权威验证，因为它脱离原 reactor 后解析到了本地仓库中陈旧的 sibling artifacts，产生了与当前源码不一致的大量 missing-symbol 错误。
  Evidence: 此前 30 模块 reactor compile 已成功；仅恢复 App 的后续命令却使用已安装旧制品。后续必须从完整 reactor 重跑 lint/static，不能把该 `-rf` 结果归类为当前源码的确定性编译失败。

- Observation: canonical reference/content capability 切换可在不牺牲恢复身份的情况下移除 `ObjectLocation`：恢复事实冻结稳定 `referenceKey`，对象位置只在 File owner 内部解析。
  Evidence: File `AssetContentReader.listReady/open` 成为唯一读取 seam；Task、DAG 和 Imagegen 的生产接口均不再 import Storage `ObjectLocation`。

- Observation: Activity owner snapshot 需要先把 upload session 的内部 `UploadSession` 形状压缩为 File-owned public `UploadActivitySnapshot`；直接从 File internal package 返回 `UploadSession` 会违反深模块可见性。
  Evidence: 当前 `DefaultFileActivitySource` 已切换为 `UploadSessionStore.findActivity/listActiveActivities`，但最新 reactor 仍报告两个 `DefaultFileActivitySource` 方法引用的静态方法绑定错误，需在下一切片修复并重新编译。

- Observation: Conversation 的 SSE session/sink/heartbeat 与 controller 一样属于 App transport；迁移后 Conversation 业务模块无需 Spring Web、Validation 或 Micrometer transport 依赖。
  Evidence: SSE 六个实现类及测试已迁入 `pixflow-app/src/main/java/com/pixflow/app/web/conversation/sse`，由 `ConversationSseConfiguration` 显式装配；clean test compilation 后 Conversation production 只编译业务类。

## Decision Log

- Decision: 前端 Cancel、Retry failed items 和 Clear 全部使用 opaque `activityId` 调用 Activity Command；App 只负责按 typed source 路由，File/Task owner 继续拥有状态、授权和幂等规则。直接 `/api/tasks/**` 不属于前端合同。
  Rationale: 这让 Activity 成为一个一致的用户命令面，又不把 Derived Retry 或清理业务规则搬入 App projection；也避免后续 Web 同时维护 Activity 与 Task 两套状态/操作接口。
  Date/Author: 2026-07-20 / Codex

- Decision: App 只拥有进程入口、传输适配、跨上下文 adapter、进程配置和应用级 read model，不拥有 Asset、Task、Conversation、Vision、Proposal 或 Rubrics 业务规则。
  Rationale: 组合根可以同时依赖多个模块来接线，但业务规则留在 owner 深模块才能保持依赖单向并可独立测试。
  Date/Author: 2026-07-19 / Codex

- Decision: 所有前端 REST/SSE/STOMP controller 物理归属 `pixflow-app`；业务模块删除自身 Web controller，只暴露 application API、command/query service、事件和 owner-defined ports。
  Rationale: `frontend/api.md` 是一个进程级契约，统一入口便于认证、分页、错误 envelope、泄密过滤和路由负面测试；这也落实父计划“App 提供 HTTP/SSE/STOMP controllers”的约束。
  Date/Author: 2026-07-19 / Codex

- Decision: App 不得 import 任一模块的 `internal..`、`infra..`、`persistence..`、`runtime..`、mapper/entity 或非公开 domain 包。缺少能力时在 owner 模块新增最窄 public query/command/event，不把 mapper 包改名为 `api` 来绕过边界。
  Rationale: 包名伪装不能形成深模块；公开接口必须表达调用者真正需要的稳定事实，而非数据库行。
  Date/Author: 2026-07-19 / Codex

- Decision: 全局 Activity 是 App-owned 持久 read model，但其状态由 File/Task 等 owner 的公开事件和快照派生；App 不直接读 owner 表。
  Rationale: Activity 是跨上下文前端投影，不属于单个业务模块。持久 current projection、单调 sequence、delivery outbox 和周期对账共同提供冷启动恢复与消息丢失修复。
  Date/Author: 2026-07-19 / Codex

- Decision: 生产必需能力使用显式构造器/Bean 依赖并 fail fast。Noop 只允许在明确的模块单测配置中使用，不作为完整 App 的默认生产降级。
  Rationale: 一个能启动但没有权限证明、Proposal handler、trace、发布桥或 Activity 的进程不是可工作的 PixFlow。
  Date/Author: 2026-07-19 / Codex

- Decision: 数据库采用开发期目标基线重建，不兼容旧 schema。各 owner 保留自己的 target-baseline SQL，App 以明确顺序运行每个 location，并用独立 history table 解决各模块重复 V1 版本；不启用 `baselineOnMigrate`。
  Rationale: 用户明确要求重构不保留兼容代码，仓库仍处开发阶段；显式多 location 协调既保留 schema ownership，也能让空库启动可验证。
  Date/Author: 2026-07-19 / Codex

- Decision: 每批测试前先运行静态检查。Checkstyle 发现只记录，不在本专项中修复，也不新增/修改 suppression；编译错误、SpotBugs High、分析器崩溃和配置解析失败仍是阻断项。
  Rationale: 这遵守用户指定的验证顺序，并与活动 Lint 计划的文件所有权分离。Checkstyle 记录必须包含文件、规则、行号和数量，不能把工具失败误记为风格问题。
  Date/Author: 2026-07-19 / Codex

## Outcomes & Retrospective

已完成第一批后端边界迁移及其审查收口，但专项计划尚未完成。Task 授权事实、File 内容读取、Vision trigger publisher、全部现有 Web controller 与 Conversation SSE 已回到正确 owner/public seam；执行源图只冻结 canonical reference，旧 custom-download wire model、runtime image wrapper、生产 Noop trace、默认 dev profile 和业务模块 Web 依赖已删除，没有保留兼容入口，也没有修改前端。

当前结果仍不足以证明 App 是完整组合根：Activity owner snapshot/reconciliation、数据库全局 baseline、完整 wiring contract 和 fail-fast 矩阵尚未完成。Activity 核心 projection/outbox、REST/STOMP dispatcher 和旧 generic progress 删除已落地；在 owner snapshot 扩展后，最新 reactor 被 File source 编译错误阻断，不能声明该后续切片已验证。此前 30 模块 lint/static reactor 与 9 项 Activity/架构定向测试成功；完整测试不作为本轮推进条件，既有 Docker 阻断仍保留为环境记录。

前端目标接口现已成为可独立实施的 wire reference：公开 envelope、分页、消息/SSE 安全投影、Materials/Outputs read model、Product Visual Analysis、Activity snapshot/frame/commands 和下载请求均有明确字段。该文档更新不表示现有 Controller 或 `pixflow-web` 已对齐；删除旧 Task/Archive/multipart 等实现仍属于本计划 Milestone 3/6 和父计划 Milestone 7。

## Context and Orientation

`pixflow-app/pom.xml` 是可部署模块，当前直接依赖主要 infra、harness、agent 和 business modules。`pixflow-app/src/main/java/com/pixflow/app/PixFlowApplication.java` 是进程入口，默认 component scan 仅覆盖 `com.pixflow.app`，其他模块通过 Spring Boot AutoConfiguration imports 装配。这一边界应保留：模块不能依靠扩大 component scan 偶然被发现。

“组合根”是应用中唯一知道具体模块如何拼接的位置。它可以创建一个实现 Task-owned `GeneratedAssetPublicationPort` 的 adapter，并把调用转给 File public publisher；它不能直接查 `asset_image` 或 `process_task` 表。“传输适配器”是把 HTTP、SSE 或 STOMP 请求变成 owner public command/query，并把返回值投影成 `frontend/api.md` 形状的薄层；它不能在 controller 中重新实现权限、状态机、幂等或删除规则。

当前 App 包含 `ai/`、`auth/`、`config/`、`download/`、`permission/`、`progress/`、`proposal/`、`task/` 和 `vision/`。其中 AI Cache adapter、Task-to-File publication adapter、File-to-Vision bridge 和 Proposal validation-to-publication adapter 的方向正确，但公开边界和完整装配仍不够。`progress/` 目前是通用 STOMP notifier，实际 Conversation bridge 使用 conversation destination；目标全局 Activity 只能推 `/user/queue/activity`，Agent 文本继续走 SSE，不能混用一个任意 channel 字符串接口。

App 子范围允许对 owner 模块做窄改动：新增 public port/query/event，删除模块 Web controller，移动 wire DTO，增加 AutoConfiguration 公开 Bean，以及补架构测试。它不允许借机重写 DAG、Task、Vision、Rubrics 等业务内核。若 owner 模块尚受其他活动计划修改，先在双方 Progress 记录文件所有权，等 public API 稳定后再接线。

## Required Reference Reading

每次开始或恢复本计划，先读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、`docs/design-docs/exec-plans/` 下全部活动计划、`docs/design-docs/design.md`、`CONTEXT-MAP.md` 和 `docs/development/linting.md`。然后读 `docs/adr/0001-work-unit-checkpoints.md` 至 `0008-keep-only-current-product-visual-facts.md`；这些 ADR 分别约束 checkpoint、fencing、derived retry、current visual facts、canonical reference、generated asset publication、ephemeral Proposal 和只保留当前视觉事实。

Milestone 0-2 必须读 `docs/design-docs/base/contracts.md`、`base/asset-references.md`、`infra/auth.md`、`infra/permission.md`、`infra/ai.md`、`infra/cache.md`、`infra/mq.md`、`infra/storage.md`、`harness/tools.md`、`harness/loop.md`、`harness/eval.md`、`harness/state.md`，以及触达模块的 `CONTEXT.md`。重点读每份文档的 Responsibility、Contracts、Invariants、Failure/Recovery 和 Verification 段。

Milestone 2-5 必须读 `module/file.md`、`module/conversation.md`、`module/dag.md`、`module/imagegen.md`、`module/task.md`、`module/vision.md`、`module/memory.md`、`module/rubrics.md` 和 `agent/agent.md`。Rubrics 接线还必须读活动的 `rubrics-deep-module-refactor-plan.md`、`pixflow-module-rubrics/CONTEXT.md` 及其模块 ADR；在 Rubrics public API 未稳定前不写 App 临时 adapter。

Milestone 3-6 必须完整重读 `frontend/README.md`、`frontend/product.md`、`frontend/api.md`、`frontend/transport-api.md`、`frontend/shell-routing-auth.md`、`frontend/stores-runtime.md`、`frontend/chat.md`、`frontend/files.md`、`frontend/tasks.md` 和 `frontend/upload.md`。这些文档只用于固定 App wire contract；本专项不修改 `pixflow-web` 业务实现，前端源码对齐仍由父计划 Milestone 7 负责。

## Plan of Work

### Milestone 0：冻结边界和失败测试

先建立可机器验证的现状清单。列出 `frontend/api.md` 的全部路由、SSE 事件和 STOMP destination，标记当前 handler、owner public API、鉴权方式和缺口。列出完整 App 所需 Bean：管理员认证与 eligibility、Permission policy 和五类 proof、Tool registry/executor/trace、Agent loop、Conversation/Proposal、Asset Reference、File upload/query/publication、DAG proposal、Imagegen proposal/executor、Task command/query/publication/recovery、Vision facts/trigger、Memory read-only、Eval trace、Activity projection、ObjectStorage、Cache、MQ、AI 和 clock。该清单写入本文 `Artifacts and Notes`，不是另建会漂移的临时文档。

在 `pixflow-app/src/test/java/com/pixflow/app/architecture/AppArchitectureTest.java` 使用 ArchUnit 替换路径字符串扫描。测试要求 App production classes 不依赖其他模块的 internal/infra/persistence/runtime/mapper/entity 包；任何非 App production class 不依赖 `com.pixflow.app..`；`@RestController` 只存在于 App；App controller 只依赖 App transport mapper 和 owner public API；业务模块不依赖 Spring MVC/SSE/STOMP 类型。保留 `PixFlowApplicationScanBoundaryTest`，并增加 App 不扩大 component scan 的守护。

新增 `AppWiringContractTest`：完整测试配置必须存在上述每个必需 Bean；逐一移除关键 proof、trace、publication、Vision trigger、Activity 或 Task/File bridge 时 context 必须以包含 Bean/port 名的诊断失败。先让测试因当前 mapper/runtime imports、模块 controller、Noop trace 和缺失 Activity 失败，作为重构基线。不要用源代码文本搜索替代字节码边界测试。

### Milestone 1：进程启动、数据库和配置

把 `application.yml` 改成环境无关基础配置，删除无条件 `spring.profiles.active=dev`。`application-dev.yml` 只在显式 `--spring.profiles.active=dev` 或测试 profile 下使用；生产所需管理员名、JWT secret、MySQL、Redis、MinIO、RocketMQ、Qdrant 和 provider 地址/密钥从环境绑定。开发默认值只能用于非敏感本地端口和明确的 fake provider，不能让空生产密钥悄悄调用外部服务。把 `.env` 加载器限制为本地开发 profile，或删除它并使用 Spring 原生 config import；不保留两套优先级不清的加载机制。

在根 dependency management 和 `pixflow-app/pom.xml` 引入 Flyway core/MySQL。各持久 owner 把当前最终 schema 整理为自己的 V1 target baseline，删除同一模块的旧增量 migration 和生产 `schema.sql` 双源；测试 fixture 可以保留在 `src/test/resources`。App 定义显式 `ApplicationSchemaMigrationConfiguration`，按 Auth -> Session/Conversation -> File -> Task/State -> Vision -> Agent/Memory -> Rubrics -> App Activity 的依赖顺序运行各 classpath location，每个 location 使用独立、稳定的 history table。禁止 `baselineOnMigrate`、`ignoreMissingMigrations` 和 out-of-order。开发库非空但 history 不符合目标基线时直接失败，并给出“备份后重建开发库”的诊断。

为每个 baseline 增加 MySQL 8.4 Testcontainers migration test，并增加 `PixFlowApplicationEmptyDatabaseStartupTest`：从空数据库启动完整 context，验证全部 mapper 和关键 query 可执行。真实进程 readiness 分别报告 MySQL、Redis、MinIO、RocketMQ、Qdrant 和必需 provider 配置；Vector 按设计可 unavailable 并使 recall 降级，但权限证明、MySQL、Task/File publication 和 Activity persistence 不可静默降级。

### Milestone 2：公开 API 与跨上下文 adapter

逐个替换当前 App 内部依赖。Task public API 新增只读的 task authorization facts、task owner/conversation lookup、Activity snapshot/event 和清理/取消 command；App 删除对 `ProcessTaskMapper`、`ProcessTask`、`TaskStatus` 的 import。File public API 提供 canonical reference expansion/content/metadata、published asset content、bundle item resolution、visual input snapshot/event 和 Activity snapshot；App 删除 `module.file.runtime` 与 image mapper/entity 依赖。Vision public API 隐藏 MQ destination 和 execution package，App 只调用 `VisionTriggerPublisher`/administration API。所有 DTO 都是 immutable record，只带 adapter 所需事实，不暴露 object key、数据库状态列或 provider 原始内容。

重写 `TaskPermissionProof` 和 `TaskProgressEventBridge`，使其调用 Task public facts/event。重写 `FileTaskAssetReader`、`FileSourceImageReader`、`FilePublishedAssetReader`、`CustomDownloadService` 和 `FileVisionBridgeConfiguration`，只依赖 File/Task/Vision owner public API。若 Custom Download 实际承担多 owner 业务编排，把 typed bundle validation/build 放到 Task 或 File 的 owner service；App 只把 wire request 中的 canonical IMAGE keys 转交，删除字符串 `type` 和 resultId/imageId 双身份。

App adapter 的命名表达两端，例如 `TaskToFileGeneratedAssetPublisher`、`FileToVisionInputEventBridge`、`TaskPermissionFactsAdapter`。每个 adapter 有纯单元 contract test，覆盖成功、owner not found、deleted/wrong-kind、timeout、duplicate event 和下游失败；adapter 不吞异常，不把 unavailable 伪装为空成功。

### Milestone 3：统一传输边界

按 `frontend/api.md` 在 `com.pixflow.app.web` 下按 auth、conversation、assets、files、vision、tasks、outputs、downloads、activities 分包建立 controller 和 wire mapper。把 Conversation、File 的现有 controller 行为迁到 App，删除模块内 controller、web DTO、MVC 依赖和 AutoConfiguration Web Bean；删除 Commerce 的非权威 HTTP controller，Commerce 只供 Agent/tool public API 使用。已有 URL 若不在权威文档中直接删除，不做 redirect 或兼容 mapping。

所有公开分页在 App 边界是 1-based，统一转换 owner query 的内部页码。所有响应使用 `ApiResponse`/`PageResponse`，所有异常经 `HttpErrorRenderer` 输出安全 code/message/details/traceId。使用 MockMvc contract tests 固定每条 route 的 method、path、body、分页、认证、错误和负面路径；特别证明 `/api/auth/register`、旧 multipart upload、旧 task-detail/confirmation challenge/pending proposal/Rubrics 路由返回 404。

Conversation message controller 保持 JSON prompt + ordered references，并输出命名 SSE：`assistant_delta`、`assistant_message_completed`、`tool_status`、`transition`、`proposal_ready`、`completed`、`error`。App 增加显式 event projector，确保 raw tool name/arguments/results、object key、permission facts、provider prompt 和内部 stack 不进入 SSE。Proposal confirm/reject body 必须为空，replay 由 Conversation owner 返回相同 taskId。

STOMP 只服务管理员全局 Activity。`/ws/progress` 若不再是权威 endpoint 就直接替换为 `frontend/api.md` 指定 endpoint；CONNECT 认证、principal 绑定和 `/user/queue/activity` user destination 有集成测试。客户端不能任意选择 channel，删除通用 `ProgressNotifier.publish(String channel, Object)` 在 App 的生产使用。

### Milestone 4：全局 Activity

在 App 内新增 `activity` 深包。`ActivityView` 是前端 read model，字段严格来自 `frontend/api.md`：稳定 `activityId`、kind、status、progress、conversation/package/task links、counts、timestamps、allowed actions 和 cursor/sequence；不复用 Task entity 或 File package DTO。

App 数据库 baseline 创建 `app_activity_projection` 和 `app_activity_event_outbox`。Projection 对 `(source_kind, source_id)` 唯一，保存 owner administrator、source revision、完整 typed fields、当前 sequence 和更新时间；outbox 以单调自增 sequence 保存 `UPSERT|REMOVE` 和当时完整 view。消费 owner event 时只接受更高 source revision，在同一事务更新 projection 并追加 outbox，因此重复/乱序事件不回退状态。Publisher 在事务提交后发送 `/user/queue/activity`，成功后标记 delivered；发送失败保留重试。Outbox 按 cursor 安全保留窗口清理，REST snapshot 仍是恢复事实源。

由于 Spring application event 不是跨进程可靠消息，Task 和 File public API 还提供管理员范围的 current Activity snapshot。App 启动后和定时任务按 source cursor 对账，补建漏失 projection、更新旧 revision、对不再存在的非终态 source 发 REMOVE。对账不能把已清理成功 Task 对应的 Generated Image 删除；Outputs 始终通过 File 查询。

实现 `GET /api/activities`、`GET /api/activities/{activityId}`、`POST .../cancel`、`POST .../retry-failed` 和 `DELETE ...`。命令根据 projection 的 typed source 路由到 File upload/extraction 或 Task public command，并由 owner 再做状态、权限和幂等检查。App 不基于显示 status 自行判定成功；命令完成后等待 owner event/对账更新 projection。测试覆盖 cold start、事件重复/乱序、DB commit 后 STOMP 失败、重连 snapshot+cursor、cancel/success race、derived retry 幂等、清理成功 Task 后 Outputs 保留和多管理员数据隔离；当前产品只有一个配置管理员，但查询仍显式按 principal scope。

### Milestone 5：显式组合接线

把隐式 component scan 类改成 `@Configuration(proxyBeanMethods = false)` 中的显式 Bean。Proposal tool 配置分别组装 DAG/Imagegen validator、Permission、Conversation `ProposalService`、canonical codec 和 descriptor；handler 本身不同时充当 `@Component` 和 Bean factory。AI admission 配置把 Cache semaphore/token bucket 接到 AI owner SPI，key namespace、cost 和 fail-closed 行为由 contract test 固定。Task/File publication、File/Vision、Permission proof、Activity 和 Eval trace 都使用相同显式方式。

为 Tools/Eval 提供真实生产 `ToolTraceSink` adapter 或把现有 Loop `TraceFanout` 作为唯一 sink 注入，删除完整 App 中的 `NoopToolTraceSink`。Trace 失败按 Eval 设计降级但必须计数并保留 turn 执行；敏感字段在进入 trace 前按 owner schema 清洗。Rubrics 只订阅 Task public immutable completed event并调用其 public service，不在 App 增加 Rubrics HTTP 路由，也不接 Memory 写回。

新增一个完整 `@SpringBootTest` wiring test，使用 Testcontainers/fake provider 启动与生产相同的 AutoConfiguration 集合，断言关键 Bean 唯一、没有 Noop、没有同接口歧义、所有 controller mapping 与清单完全一致。再添加一组 negative context tests，证明删除任一核心 adapter 时失败。可选能力必须在设计文档中逐项列出；不能用 broad `@ConditionalOnMissingBean` 把拼写错误当成合法缺省。

### Milestone 6：清理和完整验收

删除已迁移的模块 controller、App 旧 package、旧 DTO、旧 URL、旧 config key、通用 channel notifier、源码路径扫描测试、mapper scanner（若 App-owned Activity mapper 改由局部 `@MapperScan` 装配）、内部 package imports 和所有 compatibility shim。全仓搜索不得在生产源码命中 attachment、challenge/token、pending plan、bulk confirm、task output as file、Rubrics Web route 或旧 Vision/Memory writer 路径；migration 也按目标 baseline 重建，不保留旧表/列只为兼容。

执行真实 MySQL 8.4、Redis、MinIO、RocketMQ 和 Qdrant 组合测试；AI/Vision/Imagegen 使用本地 fake HTTP provider，自动测试不得调用付费 provider。故障矩阵至少覆盖 MySQL 启动失败与恢复、Redis quota fail-closed、MinIO publication failure/retry、RocketMQ publish/consume duplicate、Qdrant unavailable recall downgrade、STOMP delivery failure/outbox replay、旧 task epoch publication fence 和进程在 projection commit/outbox send 之间退出。

最后从空开发数据库启动 jar，使用管理员登录走通：上传 archive -> extraction Activity -> Materials/reference -> Chat SSE -> one-click Imagegen Proposal confirm -> Task Activity -> one Generated Image -> 清理 Task/Activity -> Outputs 图片仍可 mention。再验证非管理员历史账号登录/refresh 失败、register 404、旧路由 404、SSE/STOMP 无内部字段。只有这些可观察行为、自动测试和架构扫描全部满足，才勾选本计划并回写父计划 Milestone 7 的 App 子范围；前端源码未完成时不得把父 Milestone 7 整体勾选。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 执行。每次开始或恢复先运行：

    git status --short
    rg --files docs/design-docs/exec-plans | rg -v "completed[\\/]"
    git diff -- pixflow-app docs/design-docs config/checkstyle/suppressions.xml

不得清理用户工作。若 Rubrics、Vision、File、Task 或 Lint 活动计划仍在修改同一文件，先更新双方 Progress 写明 owner 和交接点；Maven reactor 必须串行运行，避免共享 `target/spotbugsTemp.xml` 竞争。

每个实施批次严格采用“linting 在测试前”的顺序：

    mvn -Plinters-audit -pl <本批次模块列表>,pixflow-app -am -DskipTests verify
    # 检查各 target/checkstyle-result.xml 与 target/spotbugsXml.xml，并先记录结果
    mvn -pl <本批次模块列表>,pixflow-app -am test

Checkstyle 发现写入本文 `Artifacts and Notes`，格式为日期、命令、文件、规则、行号和数量；不在本计划修复，不编辑 `config/checkstyle/suppressions.xml`，不新增 `@SuppressWarnings`。若 lint 命令因编译、SpotBugs High、插件配置、XML 解析或分析器崩溃失败，必须先修复对应非 Checkstyle 问题再测试。即使只有 Checkstyle finding，记录后仍继续测试。

边界审计命令：

    rg -n "^import com\.pixflow\..*\.(internal|infra|persistence|runtime)(\.|;)" pixflow-app/src/main/java
    rg -n "ProcessTaskMapper|AssetImageMapper|Entity\b|Mapper\b" pixflow-app/src/main/java
    rg -l "@RestController" --glob "*.java" .
    rg -n "com\.pixflow\.app" --glob "*.java" --glob "!pixflow-app/**" .
    mvn -pl pixflow-app -am -DskipTests compile

前两条、第四条生产源码预期无输出；第三条除 common exception advice 外只命中 App controller。模块 controller 删除后，其 POM 不应仅为 Web adapter 保留 `spring-boot-starter-web`。

传输与旧路径审计：

    rg -n "ConfirmationToken|ConfirmationLevel|challenge|PendingPlan|pending_plan|ATTACHMENT|source_image_ids|sourceImageIds|bulk.*confirm|/api/rubrics|/api/auth/register" . --glob "*.java" --glob "*.sql" --glob "*.yml" --glob "!**/target/**"
    rg -n "object[_-]?key|minio[_-]?key|raw.*tool|permission.*facts|provider.*prompt" pixflow-app/src/main/java/com/pixflow/app/web

目标 baseline 下生产源码和目标 migration 对旧机制零命中；第二条命中必须逐项证明只在 server-side adapter input 且不会进入 wire DTO，否则删除。

最终验证仍先 lint 后 test：

    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck
    mvn -Plinters-audit -DskipTests verify
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build
    mvn test
    mvn verify
    git diff --check
    git status --short

`mvn verify` 的 Checkstyle failure 只记录，不要求在本专项修复；SpotBugs、编译、测试和打包必须成功。为把二者分开举证，在 strict verify 仅因 Checkstyle 失败时，追加运行 `mvn -Plinters-audit verify` 并检查 SpotBugs XML/测试汇总。不得通过新增 suppression、降低规则、跳过测试或 `spotbugs.skip=true` 获得通过。

本地启动和冒烟使用显式 profile：

    mvn -pl pixflow-app -am package
    java -jar pixflow-app\target\pixflow-app-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev

启动日志必须显示各 schema location 已迁移、所有 required wiring 已验证和 readiness 状态。随后按 `frontend/api.md` 用 curl 或浏览器测试完整管理员工作流，并在本文记录关键 HTTP 状态、taskId、image referenceKey、Activity cursor 和清理后 Outputs 查询证据。

## Validation and Acceptance

架构验收要求 App production code 只依赖 owner public API 与基础框架；无 mapper/entity/internal/runtime import；模块不依赖 App；唯一 Web controller 边界在 App；默认 component scan 不扩大；App adapter 不包含业务状态机或 SQL。

启动验收要求空 MySQL 8.4 可由 App 一次迁移到目标 baseline，再次启动无 drift；非空旧开发 schema 明确失败而非兼容；基础配置不激活 dev；生产关键配置缺失给出明确诊断；必需 Bean 缺失无法得到一个“部分可用”的成功 context。

传输验收要求 `frontend/api.md` 中每个 route、分页、响应 envelope、SSE event 和 Activity STOMP frame 有 contract test；所有旧 route 返回 404；管理员认证在 HTTP 和 STOMP 都生效；安全投影不泄漏 raw tool/provider/storage/permission 数据。

Activity 验收要求 snapshot + sequence/cursor 可恢复，重复与乱序事件幂等，commit 后推送失败可重放，周期对账能修复漏事件，清理 Task/Activity 不删除 Generated Image，cancel/success race 和 derived retry 由 owner 状态机决定。

业务链验收要求 canonical Asset Reference 始终由后端生产；Proposal 展示前完整校验且一次确认幂等创建 Task；DAG/Imagegen/Task/File/Vision/Memory/Rubrics 均通过公开接口组合；Tool trace 不落 Noop；Rubrics 不写 Memory，也不暴露前端 route。

质量验收要求所有测试前有对应 lint 记录；Checkstyle finding 如实记录且不在本专项修复；无新增 suppression；SpotBugs High、编译、测试、打包、真实依赖测试和端到端冒烟全部通过。Docker 或外部服务不可用只能记为环境阻断，不能记为通过，也不能删除/skip 测试。

## Idempotence and Recovery

搜索、lint、compile、test、package、context test、Activity 对账和 outbox publisher 都可重复运行。Activity upsert 用 source revision 防止倒退，outbox 用 event identity/sequence 防止重复发送，owner command 自身仍以 proposalId、retry business key 或 owner requestId 幂等。

数据库基线重建是破坏性开发操作。执行前确认目标是本地/临时测试库，导出有价值数据并记录备份位置；绝不对未知共享或生产数据库自动 clean。目标 baseline 不匹配时停在明确错误，由人确认后重建，不加 legacy migration、兼容 reader 或 `baselineOnMigrate` 绕过。

任何里程碑中途停止时，拆分 Progress 为已完成与剩余，并记录 lint/test 证据。不得使用 `git reset --hard`、整仓 restore 或删除用户修改。删除旧 controller/adapter 时同时迁移其调用方和测试；不要留下旧新两套路由等待“之后再删”。

## Artifacts and Notes

计划创建时的 App 跨模块主链：

    HTTP/SSE/STOMP
      -> App transport adapters
      -> owner public command/query APIs
      -> owner domain/persistence

    File public events/snapshots ----\
                                    -> App Activity projection/outbox -> REST snapshot + user STOMP
    Task public events/snapshots ----/

    DAG/Imagegen validation -> App Proposal tool adapter -> Conversation ProposalService
    Task GeneratedAssetPublicationPort -> App adapter -> File GeneratedImagePublisher
    File visual-input event -> App adapter -> VisionTriggerPublisher
    Tools trace -> App/Loop trace adapter -> Eval

初始 Checkstyle 记录：`config/checkstyle/suppressions.xml` 仍包含 App AI、Auth、Config、Download 和 Task 文件的精确条目。这些条目由 `lint-baseline-remediation-plan.md` 管理；本计划不修改。实施者首次 audit 后在此追加未被当前 baseline 隐藏的新 finding。

目标 controller 清单必须从实施时的 `frontend/api.md` 逐项抄录并核对。当前顶层分组是 Auth、Conversations/Messages/Proposals、Asset References、Uploads/Packages/Images、Product Visual Facts、Outputs、Global Activity Commands 和 Downloads；Rubrics、Settings、Register、legacy Files、direct `/api/tasks/**` 和 standalone Task detail 不在合同中。

2026-07-20 第一批实施与验证记录：

    mvn -pl <本批覆盖的 30 个模块> -am -DskipTests compile
    结果：约 02:15 完成 BUILD SUCCESS。

    mvn -pl <File, Imagegen, Task, Conversation, Commerce, Vision, Rubrics, App 及其 reactor 依赖> -am -DskipTests verify
    结果：File、Imagegen、Task、Conversation、Commerce、Vision、Rubrics 的 production/test compilation 均已到达，Checkstyle 0 violations，SpotBugs audit 已运行；App 起初仅因 `FileVisualAssetReaderTest` 使用 `content()` 而非 `source()` 在 testCompile 失败，该测试随后已修正。

    mvn ... -rf :pixflow-app
    结果：解析到本地仓库的陈旧 sibling artifacts，出现大量 missing-symbol；该结果不代表完整 reactor 的当前状态，必须以从 reactor 根重新执行的 lint/static 为准。

    2026-07-20 12:45+08:00 / mvn -Plinters-audit -pl <File, Imagegen, Task,
    Conversation, Commerce, Vision, Rubrics, App> -am -DskipTests verify
    结果：修正 FileVisualAssetReaderTest 的 ReopenableImageSource 方法名后，30 模块
    reactor BUILD SUCCESS；SpotBugs audit 完成。App 记录 3 条新增 Checkstyle
    EmptyLineSeparator：FileController.java:43、FileController.java:44、
    MessageController.java:22；按本计划只记录，不修改 suppression 或源码布局。

    2026-07-20 12:46+08:00 / mvn -pl <同批模块> -am test
    结果：在到达本批 owner 模块前被 pixflow-eval 的既有
    TraceRecorderTest.queryAndErrorRecorderAttachTurnErrors 阻断（期望 ABORTED，实际 OPEN）；
    infra-storage 的 4 项 MinIO Testcontainers 测试因本机无 Docker 跳过。该命令不记为
    本批测试成功，后续使用公开 seam 定向测试验证已改范围，并保留 Eval/Docker 阻断。

本批已取得修正后的完整 lint/static 成功记录，并按 lint-first 约束运行后端测试：

    2026-07-20 / mvn -Plinters-audit -pl pixflow-tools,pixflow-loop,pixflow-app -am -DskipTests verify
    结果：30 模块 reactor BUILD SUCCESS，SpotBugs audit 全部完成；App 仍仅有计划已记录的
    3 条 EmptyLineSeparator（FileController.java:43/44、MessageController.java:22）。

    2026-07-20 / mvn -pl pixflow-tools,pixflow-loop,pixflow-module-dag,
    pixflow-module-imagegen,pixflow-module-task,pixflow-conversation,pixflow-app -am
    -Dtest=<本批 16 个公开 seam/架构测试> -Dsurefire.failIfNoSpecifiedTests=false test
    结果：reactor BUILD SUCCESS；App 汇总 19 tests，其他选定模块 tests 全部零失败/错误/跳过。

    2026-07-20 / mvn test
    结果：在 infra-cache 的 RedisDisconnectFailureInjectionTest 被
    `Could not find a valid Docker environment` 阻断（20 tests，1 error，13 skipped），
    尚未到达本批模块；不记为完整测试成功。

没有运行任何前端命令，也没有完成 Activity owner 对账、数据库全局 baseline 或端到端工作。File/Vision/Rubrics
内核中的在途改动分别归属其活动/已完成专项计划，本 App 切片只消费已稳定的 public seam，不回滚或扩写其业务内核。

    2026-07-20 / mvn -pl pixflow-app -am -DskipTests clean test-compile
    结果：在 owner snapshot 扩展前 30 模块 BUILD SUCCESS；随后 File Activity source 改为
    public UploadActivitySnapshot 后，最新编译只剩 DefaultFileActivitySource 两处静态
    `upload` 方法引用绑定错误，未把该红灯记为完成。

## Interfaces and Dependencies

App 最终只消费语义等价的 owner APIs。名称可随 owner 当前命名调整，但职责不可退回 mapper：

    public interface TaskAuthorizationFactsQuery {
        TaskAuthorizationFacts prove(String taskId, AuthenticatedPrincipal principal,
                                     TaskCommandType command);
    }

    public interface TaskActivitySource {
        Page<TaskActivitySnapshot> listCurrent(AdministratorId administratorId,
                                               SourceCursor cursor, int size);
    }

    public interface FileActivitySource {
        Page<FileActivitySnapshot> listCurrent(AdministratorId administratorId,
                                               SourceCursor cursor, int size);
    }

    public interface ActivityCommandRouter {
        void cancel(ActivityIdentity activity, AuthenticatedPrincipal principal);
        Optional<String> retryFailed(ActivityIdentity activity, AuthenticatedPrincipal principal);
        void clear(ActivityIdentity activity, AuthenticatedPrincipal principal);
    }

Activity source snapshot/event 必须包含稳定 source identity、单调 owner revision、kind/status、进度、关联 ID、时间和 owner-允许动作；不得包含 mapper entity、object key 或任意 JSON Map。App 的 `ActivityProjectionService` 提供：

    public interface ActivityProjectionService {
        ActivityPage list(AuthenticatedPrincipal principal, int page, int size);
        ActivityView get(AuthenticatedPrincipal principal, String activityId);
        void accept(ActivitySourceEvent event);
        ReconciliationResult reconcile(AuthenticatedPrincipal principal);
    }

Task/File publication、Vision bridge 与 Proposal adapter 继续遵守父计划接口：Task owns `GeneratedAssetPublicationPort`，File owns `GeneratedImagePublisher` 和 Asset Reference APIs，Vision owns `VisionTriggerPublisher`，Conversation owns `ProposalService`，DAG/Imagegen 只返回 validated immutable payload。App 不把这些 owner DTO 搬进 `pixflow-contracts`。

依赖方向最终为所有 infra/harness/module/agent -> 不依赖 App；App -> 所有需要装配的 public API。`pixflow-contracts` 只保留真正跨上下文的纯 shape，不能成为 App 为消除编译错误而堆放 DTO 的中转站。

## Revision Notes

2026-07-20 / Codex: 按前端设计补全目标 wire contract。`frontend/api.md` 现在明确 target authority、common envelope、公开 DTO、Materials 图片详情、Outputs hierarchy、四态 Product Visual Analysis 投影、Activity snapshot/frame/action 和 removed routes；Cancel/Retry failed/Clear 统一为 Activity Command，Task 仍在 owner public API 后拥有业务规则。同步更新 Web context 和 `frontend/tasks.md`。本次只修改设计与 living plan，不宣称当前后端或前端实现已对齐，也不勾选任何整体 Milestone。

2026-07-20 / Codex: 收口第一批审查发现并同步 living facts。执行源图已切换为 canonical reference + File content capability，删除 snapshot/runtime wrapper 与 `ObjectLocation` 泄漏；Conversation SSE 和 controller 物理归 App；删除生产 Noop trace，Tool executor 使用 turn-scoped 真实 sink。30 模块 lint/static 与本批定向测试成功，完整 `mvn test` 被 Docker 环境阻断；Activity、Flyway、wiring/fail-fast 与真实依赖验收仍未完成，所有整体里程碑保持未完成。未修改前端或 suppression，也未把并行 File/Vision/Rubrics 内核改动归入本切片。

2026-07-20 / Codex: 继续 App 后端切片并记录停止点。Activity typed projection、source revision 幂等、JDBC projection/outbox、独立 App activity migration、REST snapshot/detail/command 路由和 `/user/queue/activity` dispatcher 已写入；旧 generic STOMP progress、Conversation progress bridge、Task progress bridge 已删除。Task/File owner snapshot 与 cleanup seam 正在接线，最新 File 编译因 `DefaultFileActivitySource` 的 `upload` 方法引用错误暂停；未修改前端、未执行全仓测试、不勾选任何整体里程碑。

2026-07-20 / Codex: 记录当前切片完成事实。App-owned Activity 核心模型、source revision 去重、JDBC projection/outbox 持久化、`db/app-activity` Flyway location、管理员 snapshot/detail/cancel/clear REST 合同和 `/user/queue/activity` STOMP dispatcher 已完成；生产 generic progress notifier 及 Conversation/Task progress bridge 已删除。Task/File public Activity snapshot 与 cleanup API 已接入工作树，Task cleanup 保留 File-owned Generated Image。当前唯一已知红灯是 `DefaultFileActivitySource.find/listCurrent` 对静态 `upload` 方法使用了实例方法引用，导致 `mvn -pl pixflow-app -am -DskipTests test-compile` 在该文件失败；尚未宣称 Activity owner 对账、完整 wiring/Flyway 基线或端到端验收完成。按用户要求不修改前端、不以全仓测试结果阻断推进，也未提交或暂存。

2026-07-19 / Codex: 创建本计划。基于当前活动后端总计划 Milestone 7、App 源码/测试、权威前端 API、Context Map 与 ADR 完成现状审计；固定 App 为唯一传输与组合根，明确不保留旧代码兼容，新增 Activity 持久投影、目标数据库基线、owner public API、fail-fast wiring 和 lint-before-test 顺序。按用户要求，Checkstyle 只记录且由独立 Lint 计划负责修复。
