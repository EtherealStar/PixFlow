# 将各属主模块面向前端的旧接口一次性切换到目标 Wire Contract

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`，是 `docs/design-docs/exec-plans/backend-architecture-alignment-refactor-plan.md` Milestone 7 的前端合同专项计划，并与 `docs/design-docs/exec-plans/app-composition-root-completion-refactor-plan.md` 的 App 传输边界工作互为补充。后者负责组合根、Activity 持久投影、数据库启动和 fail-fast 装配；本文负责把 Auth、Conversation、File、Task、Vision 和 App 当前向浏览器暴露的旧路由、旧 DTO、旧 SSE/STOMP 事件与 `pixflow-web` 旧调用一次性替换为 `docs/design-docs/frontend/api.md` 规定的新合同。执行者只依赖当前工作树和本文，也应能完成实现与验收。

这是开发期重构，不是兼容迁移。不得保留旧 controller、旧路由别名、旧字段、双 DTO、双写、legacy reader、前端 fallback normalizer、隐藏 feature flag 或“先保留以后删除”的 wrapper。数据库使用目标 fresh-schema；若本地数据有价值，先导出，再重建，不以生产兼容代码承接旧结构。

## Purpose / Big Picture

完成后，浏览器只看到一个由 `pixflow-app` 提供的、稳定且安全的进程级合同。管理员可以登录，浏览和真实删除 Conversation，以 ordered Asset References 发送消息并消费安全 SSE；可以通过 Materials 浏览 Original Images、编辑 Product Visual Analysis，通过 Outputs 浏览脱离 Task 生命周期的 Generated Images；可以通过一个全局 Activity 快照和一个管理员 STOMP 订阅取消、重试或清理工作；可以用 canonical IMAGE reference 创建批量下载。旧 Task detail/result/download API、旧 archive、multipart 上传、Rubrics/Settings、task/package 专属进度频道和内部 tool trace 不再穿过传输边界。

业务规则仍归 owner 深模块。`pixflow-app` 只持有 HTTP/SSE/STOMP wire DTO、认证后的参数适配、跨上下文装配和跨模块 read-model 投影；它不直接读取 mapper/entity，也不把 Task、File、Conversation 或 Vision 的 persistence row 当 JSON 返回。File 继续拥有 Asset Package、Original Image、Generated Image 和 canonical Asset Reference；Conversation 继续拥有消息和临时 Proposal；Task 继续拥有执行、Derived Retry 与清理规则；Vision 继续拥有当前 Product Visual Facts；App 继续拥有 Global Activity 和进程级 transport。

可以通过一条可观察路径证明结果：从空开发库启动应用，管理员分片上传 archive，Activity 显示 Upload/Extraction；Materials 返回安全的 Package/Original Image DTO；Chat mention canonical key 并收到不含 raw tool 数据的 Proposal；确认后 Activity 显示 Task；完成后 Outputs 返回新的 Generated Image；清理 Activity 后该图片仍可浏览、下载和 mention。旧 `/api/tasks/**`、`includeArchived`、multipart upload、`/ws/progress`、`/rubrics` 等路径或前端状态均不存在。

## Progress

- [x] (2026-07-21 00:07+08:00) 阅读 `AGENTS.md`、`PLANS.md`、全部当前 ExecPlan、`docs/design-docs/index.md`、总体设计、Context Map、相关模块 `CONTEXT.md`、前端模块设计和权威 `frontend/api.md`。
- [x] (2026-07-21 00:07+08:00) 审计当前 App controllers、公共 envelope/page/error、Conversation/File/Task/Vision DTO、Activity projection、Generated Image publication、前端 API adapters、SSE/WS runtimes、stores、routes 和现有 contract tests。
- [x] (2026-07-21 00:07+08:00) 创建本专项 ExecPlan，固定 owner API 与 wire DTO 分离、Outputs 独立持久 read model、安全 SSE、Activity-only command surface、前后端一次性切换和零兼容策略；本次未修改生产代码或前端源码，未运行构建测试。
- [x] (2026-07-21 01:29+08:00) 完成 Conversation/History 后端子切片：公开 DTO 改用 `conversationId`，删除 archive 字段与 `includeArchived`，Conversation DELETE 在回合锁和 terminal Task guard 下执行真实级联清理；History 只投影 USER/ASSISTANT，按最新页读取且页内升序。
- [x] (2026-07-21 01:29+08:00) 完成安全 Agent SSE 子切片：移除反射与任意 Map 透传，tool lifecycle 收敛为产品语言 `tool_status`，transition/error/completed 使用显式安全 record；`proposal_ready` 与 durable completed identity 仍待接通。
- [x] (2026-07-21 01:29+08:00) 完成 Materials/Asset Reference 后端子切片：Package、ingest error、Original Image 使用 owner 安全 view，补充严格分页/筛选/排序、宽高与预览到期字段，并修正 selector exactly-one 与跨源 search 语义。
- [x] (2026-07-21 01:29+08:00) 完成 Activity opaque command 与 `retry-failed` 子切片：App 通过隐藏 source target 路由 cancel/retry/clear，wire 重试响应不暴露 Task 内部统计；针对性 4 tests 通过。
- [x] (2026-07-21 01:29+08:00) App 开启 Jackson unknown-field 严格拒绝；File 预览到期时间改用注入 `Clock`。相关 reactor compile 成功。lint audit 曾运行至 180 秒超时，未产生完整报告，且按要求未修正 lint。
- [x] (2026-07-21 01:35+08:00) Vision controller 改为显式 App wire DTO，将 owner recovery-only `EXPIRED` 投影为 `PENDING`，并只在公开 FAILED 状态返回 failureCode；3 个针对性 controller tests 通过。
- [x] (2026-07-21 16:06+08:00) Milestone 0：完成精确 public route inventory、旧 route 404 守护、HTTP 专用严格 Jackson mapper 与 Auth/Message/Output unknown-field 负向合同；全局 provider/persistence `ObjectMapper` 保持原配置。
- [x] (2026-07-21 16:06+08:00) Milestone 1：完成 Auth、Conversation、History、Proposal 和 Agent SSE 单向切换；Proposal 使用 typed `ProposalReadyEvent`，Stop 在服务端发送 `completed{stopped:true}` 后结束，Web 中断只做 history reconciliation 而不重放 POST。
- [x] (2026-07-21 01:35+08:00) Milestone 2：完成 Upload、Asset Reference 与 Materials 查询/变更合同；包含安全 DTO、分页/筛选/排序、预览元数据与 selector 严格校验。
- [x] (2026-07-21 16:06+08:00) Milestone 3：建立 File-owned 持久 Outputs read model，按 Conversation -> Task -> Generated Image 查询并支持 rename/delete；删除全部旧 Task wire controller、前端 API/runtime/store/route。真实 MySQL/MinIO 集成证明仍受 Docker 阻断，计入 Milestone 7 验收而非本实现项。
- [x] (2026-07-21 01:35+08:00) Milestone 4：完成 Global Activity REST/STOMP 与 Activity-only command surface；cancel/retry/clear 使用 opaque activity identity。
- [x] (2026-07-21 01:35+08:00) Milestone 5：完成 Product Visual Analysis 的安全 wire projection、canonical bundle download 与公共错误边界；Vision recovery-only `EXPIRED` 投影为 `PENDING`。
- [x] (2026-07-21 16:06+08:00) Milestone 6：完成 `pixflow-web` API、runtime、store、route 与页面消费者原子迁移，删除兼容 normalizer 和旧产品表面；补齐 application-scoped per-conversation queue、Edit/Cancel/Continue、纯引用消息、Proposal 等待与跨 SPA 导航状态保持。
- [ ] Milestone 7：完成旧路径清零、静态门禁、模块/前端测试、真实依赖和端到端验收，并回写父计划 Milestone 7。
- [x] (2026-07-21 02:15+08:00) Milestone 6 子切片：会话列表/删除与分片上传客户端切换为单向合同，移除 `includeArchived`、archive 命名、multipart whole-file 入口和下载旧类型；同步更新会话合同测试。`npm run typecheck`、目标 Vitest 测试和 `npm run lint:audit` 通过；Task runtime、TaskDetail 和旧进度通道仍待迁移。
- [x] (2026-07-21 13:22+08:00) Milestone 6 子切片：Vision adapter 接受 `facts/writer=null` 并保持严格 DTO 校验；Materials detail 增加可取消的 2 秒 active poll、SKU/请求代际防陈旧响应、完整 facts 表单、归一化、dirty/conflict 与三路离开保护。Activity reconnect 在 REST reconciliation 期间缓存并按 sequence 回放帧，logout 释放 STOMP；Conversation adapter 删除 `BackendConversationView` fallback 并改为严格 Zod schema。前端 typecheck 与相关 12 项测试通过，完整门禁结果见本轮后续记录。
- [x] (2026-07-21 13:24+08:00) 本轮前端门禁：`pnpm --dir pixflow-web typecheck`、24 个 Vitest 文件共 115 项测试、`pnpm --dir pixflow-web build` 通过。`pnpm --dir pixflow-web lint` 记录 134 errors / 0 warnings（主要为 Vue 模板格式，另含 2 个测试 `no-base-to-string` 与 2 个 unused catch 参数）；按用户要求只记录，未执行 lint 修正或自动 fix。构建另记录 Tailwind `darkMode: false` 的既有升级警告。
- [x] (2026-07-21 13:39+08:00) `implement` 后置双轴审查修复：Agent stream failure 原子拒绝并清空 queued messages，避免 Promise 永久 pending；Activity reconnect snapshot 失败时保持帧缓冲并定时重试，成功前不放行增量；Vision 初次 transport failure 显示 Retry 且 image detail GET 可取消。新增 3 项回归测试；最终 typecheck、24 个 Vitest 文件共 117 项测试及 production build 通过。审查同时确认 history interruption reconcile、仅跨 SKU dirty guard 及 Activity command runtime ownership 尚待后续 Milestone 6 切片，不在本记录中标记完成。
- [x] (2026-07-21 16:06+08:00) 完成剩余前后端合同收口：App-owned Auth/Conversation/History/Message/Proposal DTO、typed Proposal SSE、Stop 完成事件、严格 unknown-field、精确 route inventory；Web history reconcile、application-scoped Agent runtime、structured reference chips、Activity intent routing与旧 Files/Task surface 清零。消息校验按权威合同允许 reference-only，Proposal confirm/reject 显式拒绝任何 body。
- [x] (2026-07-21 16:06+08:00) 可执行门禁：`pnpm --dir pixflow-web typecheck`、25 个 Vitest 文件共 122 项测试、production build 通过；App 合同/SSE/Proposal 目标组 23 项通过；后端 reactor test-compile 通过；四组 legacy scan 的生产源码无禁用 route、字段或前端 surface 命中。构建仅记录 Tailwind `darkMode: false` 警告。
- [ ] (2026-07-21 16:06+08:00) Milestone 7 真实依赖阻断：本机 Testcontainers 未找到 Docker。`GeneratedImagePublicationIntegrationTest` 8 项全部 skipped；全仓 `mvn verify` 首个失败为 `RedisDisconnectFailureInjectionTest` 无法启动容器，之后 reactor 模块被跳过。空库完整 App 与 MySQL/Redis/MinIO/RocketMQ/Qdrant/fake AI 的端到端链尚未执行，不能将 skipped 或未运行项记为通过。

## Surprises & Discoveries

- Observation: 后端的新路由只落地了一部分，不能把本工作理解成 controller 重命名。
  Evidence: `FileController`、`VisionFactsController` 与 Activity snapshot/cancel/retry/clear 已有目标路径；但 `TaskQueryController` 仍暴露 `/api/tasks/**`、conversation tasks/images、task result rename/delete/download，Outputs controller 完全不存在。

- Observation: 当前 controller 直接序列化 owner entity 或内部查询 DTO，已经泄漏目标合同禁止的字段。
  Evidence: `FileController` 返回 MyBatis `AssetPackage`，会暴露 `fileHash`、`minioZipKey`、`docKey`、cleanup 和 extraction internals；`AssetImageView` 暴露 `originalPath`、`groupKey`、`viewId`，却缺 `width`、`height`、`previewExpiresAt`；Conversation `MessageView` 暴露 toolCallId、compaction marker、taskId 和内部角色。

- Observation: 当前 Conversation DELETE 名义上是删除，实际仍调用 archive，且返回 DTO 仍含 `archived`。
  Evidence: `ConversationController.delete` 调用 `ConversationService.archive`；`ConversationView` 含 `id` 与 `archived`；前端仍发送 `includeArchived=false` 并把删除 intent 命名为 `archiveConversation`。

- Observation: 当前 SSE 适配器是明确的敏感数据泄漏点，而且目标 Proposal 事件尚无生产链路。
  Evidence: `SseAgentEventSink` 发送 raw `toolName`、tool input/result/metadata、assistant call identity、iteration、retry count、provider error detail 和 trace internals；前端类型也消费这些字段。生产搜索没有 `proposal_ready`，`useAgentTurn.setProposal` 目前只由测试注入。

- Observation: Outputs 不能继续从 Task execution row 临时拼装。
  Evidence: 目标合同要求 Task/Activity 清理后 Generated Image 仍按 Conversation -> Task 分组；当前 File `asset_image` 只冻结 sourceTask/result/image 和 producer lineage，没有 conversation title snapshot、task type、task created/finished facts。清理 Task 后旧查询无法重建这些分组。

- Observation: 当前前端仍广泛依赖已删除合同。
  Evidence: `api/tasks.ts`、`runtime/useTask.ts` 和 `TaskDetailPage.vue` 调 direct Task API 与 `/ws/progress`；`usePackageProgress.ts` 订阅 package topic；`api/packages.ts` 保留 multipart whole-file upload 和 `id/packageId` fallback；Conversation store 使用 archive；router 仍有 Home、combined Files、Task detail、Rubrics 和 Settings。

- Observation: 公共 envelope 已基本符合目标，但仅靠 TypeScript generic cast 和 Spring 默认绑定不能证明 wire contract。
  Evidence: `ApiResponse`/`PageResponse` 已有目标外形；前端 `request<T>` 对成功 JSON 直接断言类型，并把 `records` 自动复制为 `items`；后端多个请求 record 无 Bean Validation/unknown-field 拒绝，旧 query 参数可能被 Spring 静默忽略。

- Observation: Product Visual Facts owner API 仍含 recovery-only `EXPIRED`，不能直接作为 wire enum 返回。
  Evidence: `AnalysisStatus` 包含 `EXPIRED`，而目标 wire 只允许 `PENDING | RUNNING | SUCCEEDED | FAILED`，要求 crossing wire 前把 recovery state 投影为 PENDING。

- Observation: 前端构建不会执行 TypeScript 类型检查，旧合同测试会继续暴露已删除参数。
  Evidence: `npm run build` 成功但 `npm run typecheck` 首次因 `includeArchived` 测试参数失败；更新测试 fixture 后类型检查通过。
- Observation: 会话和上传客户端可以先完成单向切换而不影响 Vite 产物，但旧 Task 页面仍将旧路由打包进产物。
  Evidence: `vite build` 仍输出 `TaskDetailPage` 与 `useTask` chunk，且源码搜索仍命中 `/api/tasks/**` 与 `/ws/progress`。

## Decision Log

- Decision: owner application API 与 wire DTO 分离。App controller 不直接返回 entity、mapper row、provider DTO 或 owner 内部 query DTO；每个 endpoint 使用 App-owned request/response record 或显式 projector。
  Rationale: `frontend/api.md` 定义的是进程级公开合同，不等于 Java 模块接口。显式投影可固定字段白名单、安全枚举和时间格式，同时保留 owner 深模块的演进空间。
  Date/Author: 2026-07-21 / Codex

- Decision: 所有 HTTP/SSE/STOMP adapters 物理归属 `pixflow-app/src/main/java/com/pixflow/app/web/**`；owner 模块只暴露语义化 command/query/event，不依赖 Spring Web。
  Rationale: 这延续 App 专项计划已冻结的唯一传输边界，并允许统一认证、分页、错误 envelope、严格输入、泄密守护和 route inventory tests。
  Date/Author: 2026-07-21 / Codex

- Decision: 不在 App 中复制业务查询。若现有 owner API 不能提供目标事实，先在 owner 模块新增最窄 public query/command，再由 App 投影；不得让 App import mapper/entity/internal/persistence/runtime 包。
  Rationale: App 是组合根，不是第二个业务层或跨库 SQL 层。
  Date/Author: 2026-07-21 / Codex

- Decision: Outputs 是 File-owned、由发布与 Task 完成事实驱动的持久 read model，不依赖可清理的 Task execution row。
  Rationale: Generated Image 的可用性和分组必须在 Task/Activity 清理后继续存在。File 已拥有 Generated Image 生命周期，最适合保存 conversation/title/task 快照和图片 lineage；Task 只通过稳定 publication/completion seam 提供事实。
  Date/Author: 2026-07-21 / Codex

- Decision: Agent SSE 使用固定七事件白名单和强类型安全 payload；不从任意 Map/record 反射字段后透传。Proposal publication 必须产生显式 typed `proposal_ready` 事件，tool lifecycle 只能映射为 product-language `tool_status`。
  Rationale: raw tool、provider、permission 与 trace 数据一旦进入通用 map，后续黑名单无法可靠防泄漏。白名单 projector 才能形成可测试的安全边界。
  Date/Author: 2026-07-21 / Codex

- Decision: Activity controller 只接收 opaque `activityId`。projection repository 内部保存 source kind/id，command application service 解析并路由；controller 和 Web 不拆 activityId，也不从 status 推断操作。
  Rationale: 当前 controller/router 解析 `task:`、`upload:`、`package:` 前缀，已经把内部 identity grammar 暴露为隐式合同。目标合同只承诺 opaque identity 和 `allowedActions`。
  Date/Author: 2026-07-21 / Codex

- Decision: 前后端按 milestone 做一次性垂直切片。同一切片同时修改 owner API、App adapter、contract tests 和对应 Web consumer，随后删除旧实现；不建立双路由或兼容 normalizer 作为过渡。
  Rationale: 用户明确要求重构不保留旧代码；仓库处于开发阶段，可用 tests 保证每个小切片工作，而无需生产双合同。
  Date/Author: 2026-07-21 / Codex

- Decision: 后端所有 public page/filter/sort/enum 输入显式校验。1-based page、size 上限、selector 互斥、重复 exclusion 上限和 removed query 参数在 transport 边界拒绝，不能依赖 mapper 截断或 Spring 静默忽略。
  Rationale: 同一个错误输入在不同 owner 中若被静默处理，会让分页、缓存和 Web URL 状态不可预测。
  Date/Author: 2026-07-21 / Codex

## Outcomes & Retrospective

Milestone 0-6 的单向代码迁移已完成。App 现在是唯一 HTTP/SSE/STOMP 边界；Conversation、Materials、Outputs、Activity、Vision、Download 与 Auth 都通过 App-owned wire DTO 投影，owner 类型不再直接充当 JSON 合同。旧 Task/Rubrics/register/archive/multipart/progress 路由与前端页面、store、runtime 已删除。Agent SSE 只保留七类安全事件，Proposal 与 Stop 有显式终态；Web 的 per-conversation queue、draft、references 与 Proposal 在 SPA 生命周期内由 application runtime 持有。

Milestone 7 尚未完成，唯一已确认的环境阻断是 Docker 不可用。纯 JVM/App 合同和完整前端门禁通过，但需要容器的 File/Outputs 与 Redis failure-injection 无法有效运行，全仓 verify 因首个 Docker 错误提前停止，完整真实依赖 E2E 也未执行。因此父计划 Milestone 7 与 App 专项 transport 最终验收保持未勾选；恢复 Docker 后应从 `mvn verify`、零 skip 容器测试和空库端到端链继续。

## Context and Orientation

## 2026-07-21 Implementation Record

- 已新增 Activity strict API/store/runtime、Materials/Outputs/Vision 前端 adapters 和页面；旧 Task、Rubrics、Home、Combined Files 路由及对应公开组件已删除。
- File 输出发布现在冻结 conversation/title/task type/task timestamps 到 `generated_output_context`，Task terminal 后记录完成时间；App 提供 `/api/outputs/**` 查询、rename、delete wire DTO。
- Product Visual Analysis 页面已接入 GET/PUT/reanalyze，支持版本化保存、request identity、2 秒轮询、脏状态离开保护和冲突提示。
- 验证更新：`pnpm --dir pixflow-web typecheck`、25 files/122 tests、`build` 通过；App 目标合同组 23 tests 与 reactor test-compile 通过；生产 legacy scans 无禁用合同命中。
- lint 按用户要求只记录、不修正；前次完整前端 lint 为 134 errors / 0 warnings，本轮最终 lint audit 尚待执行。Vite 仍报告 Tailwind `darkMode: false` 升级警告。
- 尚未完成：Docker-backed tests、全仓无 skip `mvn verify`、真实依赖完整 E2E、最终 `code-review` 与 lint audit。当前首个硬阻断是 Testcontainers 无可用 Docker，而非功能断言失败。

仓库根目录是 `D:\study\PixFlow`。`pixflow-app` 是唯一可部署 Spring Boot 进程；当前 Web adapters 位于 `pixflow-app/src/main/java/com/pixflow/app/web/`。`pixflow-web` 是 Vue 3 应用。`pixflow-common` 提供 `ApiResponse`、`PageResponse` 和错误归一化；`pixflow-infra-auth` 拥有 Configured Administrator；`pixflow-conversation` 拥有 Conversation、Message Reference、Agent Turn 和 Proposal；`pixflow-module-file` 拥有 Asset Package、Original/Generated Image、Asset Reference 与删除；`pixflow-module-task` 拥有执行和 Derived Retry；`pixflow-module-vision` 拥有 Product Visual Facts；App 的 `com.pixflow.app.activity` 拥有跨上下文 Activity projection。

“wire DTO”指经过 JSON/SSE/STOMP 传给浏览器的公开形状。“owner API”指 Maven 模块向其他后端模块暴露的 Java command/query/event。两者不能自动等同：owner API 可以包含后端需要的稳定事实，但 wire DTO 必须进一步执行管理员 scope、字段白名单、enum projection、预览 URL 和安全错误投影。对象位置、hash、provider payload、permission proof、内部 trace 和 persistence 状态都不得成为 wire 字段。

执行前必须重读当时 `docs/design-docs/exec-plans/` 下所有活动计划。当前 Rubrics 和 Memory 计划可能修改 Task/File/App public seams；工作树已有用户修改。每个批次先检查 `git status --short` 与相关 diff，在现有修改上做最小补丁，不 restore、不重置、不覆盖。App 专项若同时修改同一 controller、Activity 或 baseline，先在两个计划的 Progress 记录文件所有权和交接点，Maven reactor 串行运行。

## Plan of Work

### Milestone 0：冻结唯一合同与失败测试

先把 `frontend/api.md` 的 route inventory 写成 App 集成合同测试。测试必须覆盖 Auth、Conversation/History/Message/Turn/Proposal、Asset Reference、Upload/Materials、Vision、Outputs、Activity、Download 的每个 method/path；同时证明 register、archived query、multipart upload handler、direct Task routes、Rubrics/Settings API 和旧 WebSocket endpoints 不存在。`/api/auth/register` 明确返回 404；对于与现有 GET 同路径的非法 method，可以由 Spring 返回 405，但不得存在可接受 multipart body 的 handler。

在 `pixflow-app` 建立 transport-only DTO/projector 包，例如 `com.pixflow.app.web.contract`、`web.assets.dto`、`web.outputs.dto`。这些包只能依赖 owner public API 和 common primitives，不引用 entity/mapper/internal/persistence/runtime。新增 ArchUnit 守护，禁止 controller 方法返回 `AssetPackage`、`AssetImage`、`TaskResultView`、`TranscriptMessageView` 或任意 `..entity..`/`..mapper..` 类型。

冻结严格输入机制。定义共享的 App-side `PublicPageRequest` 或等价 validator，要求 `page >= 1`、模块定义的 size 上限、sort/filter enum 精确匹配。对 JSON command 使用 Bean Validation 和明确 unknown-field rejection；对 Asset Reference selector 执行 exactly-one；对旧 `includeArchived`、`archived`、message `packageId/attachments`、download `type/imageId/resultId`、confirm challenge/token/body 和随机 idempotency header 增加 4xx 合同测试。不要全局改变 Jackson 后影响 provider/persistence JSON；严格反序列化应限定在 HTTP request DTO 或 App HTTP message converter。

保留 `ApiResponse`/`PageResponse` 外形并补集成测试：成功 `code=OK`，失败保留 meaningful HTTP status、safe message、details 和 traceId。验证参数错误映射为稳定 4xx 业务码，不落为 500。所有公开时间由 Jackson 输出 ISO-8601 UTC instant；公开 ID 在 Web 中只按 opaque string/number field 保存，不参与语义解析。

Milestone 完成时，新的失败测试应先证明当前旧接口和字段仍存在，再随下列切片逐项转绿。测试清单本身不允许通过放宽断言来适配旧实现。

### Milestone 1：Auth、Conversation、History、Proposal 与安全 SSE

Auth 当前行为基本符合目标，但 App 不再直接返回 infra DTO。为 login/refresh/me 增加 App wire records，显式投影 `accessToken`、`accessTokenExpiresAt` 和 `user(userId, username, displayName)`；logout 返回 `data:null` 并清 cookie。保留每次 login/refresh/me/authenticated request 对 Configured Administrator eligibility 的复核，增加历史账号失败和 register 404 集成测试。

在 `pixflow-conversation` 把 `ConversationView` 改为 owner public view：字段为 `conversationId,title,createdAt,updatedAt`，mapping 留在 Conversation internal package，公开 record 不 import `ConversationEntity`。删除 `archived` 字段、archive service/query、`includeArchived` 逻辑和 fresh-schema archived column。实现 real delete command：先通过 Conversation-owned `ConversationDeletionGuard` 或等价窄端口确认没有 active Agent Turn 或 non-terminal Task，再事务删除 Conversation、Session messages、临时 Proposal runtime 与 Conversation-only Activity/lock；Materials 和 Generated Images 不参与级联。Task 活跃事实由 App 连接 Task owner public query，Conversation 不读 Task mapper。

把 History owner query 改为只返回 `messageId,seq,role,content,references,createdAt`。只允许 USER/ASSISTANT；tool call/result、compaction、task binding 和 trace 在 owner 内过滤。分页查询先按 `seq DESC` 选最新页，再在返回页内按 `seq ASC` 排列；页 1 是最新页。用真实 Session/Conversation 测试覆盖跨页顺序、历史 reference snapshot、删除素材后的 token 和内部角色不可见。

保留 message JSON 的 `prompt + ordered references`，补 prompt blank/reference count、最多 20 项、unknown field、canonical ownership/availability 的合同测试。Proposal confirm/reject body 必须为空；显式拒绝 challenge、answer、token、expected count 和 client idempotency key。confirm 返回 `proposalId,taskId,status=CONFIRMED`，重复 confirm 通过 proposalId 的业务幂等查询返回同一 task；repeated reject 成功且没有 synthetic history message。

替换 `SseAgentEventSink` 的反射 Map 投影。建立 typed `AgentTurnEventProjector` 和 wire payload records，只发：`assistant_delta{text}`、`assistant_message_completed{messageId,finalText}`、`tool_status{label,state}`、`transition{label,state?}`、`proposal_ready{proposalId,conversationId,proposalType,title,summary,referenceSummaries,createdAt}`、`completed{messageId?,stopped}`、`error{code,message,traceId?}`。Proposal owner 在完整校验并存入 ephemeral store 后产生 typed publication event；Loop/App 不从 raw tool result 猜 Proposal。tool label 由 server allowlist 把内部 tool identity 映射成产品语言，未知工具使用固定安全标签；arguments/results/canonical keys 永不进入 payload。`completed` 只在 durable assistant message/stop 事实确定后发送；heartbeat 是 comment。

删除旧 SSE event names `tool_call_ready/tool_started/tool_result` 以及 assistantCallId、modelTurnIndex、iteration、turnNo、retry counters、provider error fields。增加序列化快照和负向 JSON-key 测试，扫描每个事件确认不含 `toolName/toolInput/content/metadata/objectKey/provider/prompt/permission/attempt/retries`。更新 Conversation/App lifecycle tests，证明 stream 在 `completed` 前断开被视为 interrupted，客户端依历史 messageId 对账而不重放 POST。

### Milestone 2：Upload、Asset Reference 与 Materials

把 `FileService` 当前 controller-facing monolith 收敛为 File-owned public commands/queries，例如 `MaterialQueryService`、`MaterialCommandService` 和既有 `UploadSessionService`/`AssetReferenceCatalog`。具体命名可随模块语言调整，但 controller 不再获得 MyBatis `AssetPackage` 或含 storage fields 的 `AssetImageView`。File internal mapper 负责 projection，App 再映射为 wire records。

定义 Package public facts：list record 只含 `packageId,displayName,status(READY|PARTIAL),originalImageCount,skuCount,createdAt,updatedAt`；detail 可附安全 extraction diagnostics，但不能含 fileHash、archive/minio/doc key、cleanup state 或 original filesystem path。实现 package query/sort `UPDATED_DESC|UPDATED_ASC|NAME_ASC|NAME_DESC` 和 trimmed name search，默认 UPDATED_DESC；只返回 Materials 可浏览 READY/PARTIAL package。SKU view 固定为 `packageId,skuId,originalImageCount`。

重构 Original Image facts为 `imageId,packageId,skuId,referenceKey,sourceType=ORIGINAL,displayName,width,height,sizeBytes,contentType,previewUrl,previewExpiresAt,createdAt`。如果 `asset_image` 当前没有 width/height，修改 File fresh baseline/entity/ingest/publication，使图片 admission 时通过已有 bounded probe 保存尺寸；不要在每次列表请求重新 decode。预览 service 同时返回 URL 与 expiresAt，DTO 不返回 `filename/originalPath/groupKey/viewId/minioKey`。detail 返回该 view 与按 package 默认 `CREATED_DESC` 顺序计算的 previous/next。

全局与 package-scoped Original Image query 共享一个 File query object：支持 `packageId/skuId/query/sort/page/size`，sort 为 `CREATED_DESC|CREATED_ASC|NAME_ASC|NAME_DESC`，永远过滤 GENERATED。所有搜索/排序在 SQL 分页前完成，不能先取整页再在 App/浏览器过滤。rename request 只接受 displayName 并返回完整更新 view；单个 delete 返回 null。Package rename 同样只接受 displayName。错误列表使用安全 DTO，禁止 archive entry 的绝对路径或存储位置。

保留现有 chunk upload内核，但在 App 投影精确 wire fields和 enum。init 严格校验 archive extension、2 GiB ceiling、64 lowercase hex whole-file SHA-256 和固定 5 MiB chunkSize；UPLOAD/RESUME/DEDUP 字段按合同填充，uploaded/failed chunks 排序。session GET 只返回 Web 恢复需要的公开 session facts；chunk PUT 校验 content type/header/size/hash；complete body 按 owner 合同保持空或仅接受当前明确定义字段，不接受旧 multipart semantics；DELETE 返回 CANCELLED。删除 Web/后端任何 whole-file multipart handler/type/test。

Asset Reference picker 显式校验 `source xor parentKey xor nonblank query`，exclusions 最多 20 且在分页前精确排除。empty root browse 使用 MATERIALS/OUTPUTS，child 完全把 parentKey 当 opaque string，search 跨两源并只在 search record 加 sourceGroup。candidate 固定为 `referenceKey,kind,sourceType?,displayPath,hasChildren,sourceGroup?`；generated image 不出现在 PACKAGE/SKU expansion。

### Milestone 3：持久 Outputs 与旧 Task API 删除

在 File owner 建立独立 Outputs read model，推荐使用 `generated_output_conversation`、`generated_output_task` 和现有 `asset_image`/关联表，或语义等价的 File-owned target baseline。Conversation group 保存 `conversationId,titleSnapshot,generatedImageCount,latestGeneratedAt`；Task group 保存 `taskId,taskType(IMAGE_PROCESS|IMAGEGEN),generatedImageCount,createdAt,finishedAt`。这些是 Generated Image 的浏览投影，不是 `process_task` aggregate，不随 Task/Activity retention cleanup 删除。

扩展 Task-owned `GeneratedAssetPublicationPort` candidate 与 App adapter：Task 提供 conversationId、taskId、task type、task created time和发布结果事实；App 通过 Conversation public title query取得当时 title snapshot，并调用 File publication。File 在 Generated Image READY transaction 中幂等 upsert conversation/task output groups和计数。由于多 work unit 可以在 Task terminal 前逐个发布，Task terminal public event必须在 App bridge中调用 File completion seam，冻结 task finishedAt；事件重复必须幂等。Conversation 在 Task 运行时不可删除，所以 publication 不能以 missing title静默降级；缺失 owner facts应 fail closed并进入现有 publication recovery。

Generated Image wire view固定为 `imageId,referenceKey,sourceType=GENERATED,displayName,packageId,skuId,conversationId,taskId,sourceImageId,width,height,sizeBytes,contentType,previewUrl,previewExpiresAt,createdAt`。发布 candidate 必须携带或由 File bounded probe取得 width/height/content metadata。不得返回 process resultId、candidate/object key、execution epoch、provider/model/tool或内部 lineage。Generated image rename/delete调用 File owner command；删除字节和 processable row后更新/删除空 group计数，仅保留历史 mention tombstone。

在 App 新建 `OutputController` 和显式 wire projector，精确实现 `/api/outputs/conversations`、conversation tasks、task images、output image PATCH/DELETE。conversation query支持 query和四种 sort，其他两层1-based分页。增加测试证明 deleting/clearing Task execution后 Outputs三层查询仍返回图片，rename不改referenceKey，delete只删除目标Generated Image。

当 Outputs纵向切片可用后，完整删除 `TaskQueryController`。删除前端公开面的 `TaskQueryService` 方法：status、conversation task list、result list、conversation images、result download、result rename/delete；若后端内部或 Rubrics 仍需 Task facts，迁移到现有 owner-specific immutable query，而不是保留旧 service。删除 `/api/tasks/**`、`/api/conversations/*/tasks`、task result/download/cancel和conversation images mappings及 tests。Derived Retry、cancel和clear只通过 Activity application service进入 Task command，Task业务规则不搬入App。

### Milestone 4：Global Activity 与唯一命令面

在已存在的 App Activity projection/outbox基础上补齐目标合同。Activity public view保持 `activityId,kind,status,progress,conversationId?,packageId?,taskId?,timestamps,allowedActions,sequence`；kind/status必须是目标enum，progress字段名为 `completed,total,failed`。list返回 records/total/page/size/cursor，status/kind filter严格校验，detail按管理员scope读取。

修改 Activity persistence，使公开 view与隐藏 command target分离。repository内部保存 source kind/id/revision；新增 App application service语义等价于 `cancel(principal, activityId)`、`retryFailed(principal, activityId)`、`clear(principal, activityId)`，统一加载projection、验证allowedActions、用隐藏source facts路由File/Task owner并在owner重新校验。删除 `ActivityController.source()` 与 `OwnerActivityCommandRouter.fileSource()` 对activityId字符串的解析。

实现 `POST /api/activities/{activityId}/retry-failed` 空body。Task owner使用固定 `retry-failed:{sourceTaskId}` business identity、权威失败Work Unit snapshot和direct child规则；response投影为 `sourceActivityId,activityId,taskId,retryOfTaskId`，重复请求返回同一child。cancel和clear返回null；clear在owner接受后更新projection，不能先删projection再发现owner拒绝。并发success/cancel、retry/clear由owner CAS决定。

固定 STOMP handshake `/ws/activity`、CONNECT Bearer和唯一subscribe `/user/queue/activity`。frame只能是 `sequence,operation(UPSERT|REMOVE),activityId,view`，UPSERT带完整current view，REMOVE为null。dispatcher成功后才标outbox delivered；reconnect先加载REST cursor，再应用更大sequence，重复/旧frame忽略。增加鉴权、乱序、重复、delivery failure/replay和snapshot reconciliation tests。

### Milestone 5：Product Visual Analysis、Downloads 与错误安全

Vision owner现有 `VisualFactsAdministrationService`保留业务职责，App controller改用wire projector。将owner `EXPIRED`投影为 `PENDING`，只向Web返回四态；facts可与RUNNING/FAILED共存，writer只允许AI_GENERATED/ADMINISTRATOR_EDITED/null，failureCode只在terminal FAILED出现。GET/PUT/reanalyze response都返回完整同形view。

PUT request只含 `expectedVersion,facts`，对完整Product Visual Facts执行unknown/nested field拒绝、长度/数量限制和trim normalization，all-empty合法；stale version映射409 `VISUAL_FACTS_VERSION_CONFLICT`，active analysis映射409 `VISUAL_ANALYSIS_ACTIVE`。Reanalyze只含 `expectedGeneration,requestId`；同requestId重放幂等，stale generation映射409固定code。controller不返回provider、attempt、epoch或writer internals。

保留 `/api/downloads/bundle`，但request只接受 `archiveName` 与 canonical IMAGE `referenceKey + optional filename`。App service按首次出现顺序去重exact key，File resolver校验IMAGE kind/scope/availability并解析Original或Generated内容；backend sanitize并唯一化filename。response精确为 `url,expiresAt,contentType,sizeBytes`。删除前端/后端旧 `type=ASSET_IMAGE|TASK_RESULT`、imageId、resultId和Task download types；下载不再依赖Task result row。

在App安全测试中对所有HTTP/SSE/STOMP DTO执行JSON key allowlist。错误renderer保留stable code/details/traceId，但details也必须使用每个domain的safe projection，不能仅对字符串做通用sanitize后返回object key/SQL结构。非法输入、not found、conflict、auth failure和unexpected exception各至少一项集成测试，确保HTTP status与envelope一致。

### Milestone 6：一次性迁移 pixflow-web

先重构transport。`api/client.ts`不再支持任意`idempotencyKey`和multipart flags；GET仅按设计重试，mutations由owner runtime用稳定业务identity决定。删除全局`records -> items`兼容复制，统一Page类型使用`records`。让request返回unknown或接受Zod schema，每个API adapter验证目标wire DTO后再进入store；不要用TypeScript generic cast伪装验证。`ApiError`保留status/code/message/details/retryAfter/traceId。

逐模块替换API adapters：Conversation只消费`conversationId`且调用real delete，不发送includeArchived；Messages只允许USER/ASSISTANT安全view；新增Asset Reference、Materials、Outputs、Vision、Activity adapters；Downloads只发送canonical IMAGE keys。删除`api/tasks.ts`、`api/rubrics.ts`、whole-file upload、BackendConversationView/BackendPackageDetail fallback、numeric task result identity和idempotencyStore。

重写Agent event types/reducer，只处理七个安全事件。tool timeline只保存label/state，不保存toolName/input/result/metadata；transition只保存label/state；`proposal_ready`按proposalId upsert多张卡，类型用`IMAGE_PROCESS|IMAGEGEN`，turn-level completed前禁用，completed后独立confirm/reject。stream提前关闭调用history reconcile，不自动重发。Stop调用`/turns/stop`并按completed.stopped推进queue。

新增application-scoped Activity runtime：启动/重连先GET snapshot，再连接`/ws/activity`并订阅唯一user queue；按cursor/sequence幂等apply；cancel/retry/clear只传activityId。删除`useTask.ts`、`usePackageProgress.ts`、task/package topics和`/ws/progress`。Upload runtime在complete后依Activity恢复extraction，不在Composer维护package progress。

把combined Files页面拆为Materials和Outputs stores/routes/query trees，并实现Materials image detail的Vision load/2秒active poll/abort/stale response/dirty version conflict机制。Outputs按conversation/task/image lazy query，不依赖Conversation全量枚举或Task result。共享rename/delete/preview/download组件只使用imageId/referenceKey。实现global Activity panel，不恢复standalone task detail。

路由最终只有`/login`、`/chat/new`、`/chat/:conversationId`、Materials列表与detail、Outputs列表和404；`/`在鉴权后redirect到`/chat/new`。删除Home dashboard、combined`/files`、`/tasks/:taskId`、`/rubrics`、`/settings`及nav/store/api/components/tests残留。Login只显示不可交互`暂未开放注册`，从不调用register。

每个前端切片先lint/typecheck，再Vitest/build。页面层不得继续parse referenceKey、activityId或fallback字段；预签名URL只作短期显示/下载，不进持久store。

### Milestone 7：清理与端到端验收

删除旧Java/TS/Vue类型、controller、service方法、schema列、migration目标、auto-configuration bean、Web dependency、metrics和精确suppression。更新模块`CONTEXT.md`、App/父ExecPlan living sections和必要Revision Notes，只记录实际偏差；不要改写completed历史计划。

建立一个启动级route inventory test和Web API contract suite作为长期守护。后端测试必须证明完整App映射只含目标routes，旧route无handler；前端测试必须证明没有旧URL/string/topic和fallback fields。SSE/STOMP使用完整serialized JSON断言，不只测试Java record。

最后从空MySQL baseline启动完整App，走真实Redis/MinIO/RocketMQ和fakeAI provider。执行upload -> Activity -> Materials/reference -> Chat SSE/Proposal -> confirm -> Activity -> Generated Image -> Outputs -> clear Activity -> Outputs仍存在；然后验证real Conversation delete、Generated Image delete、Vision edit/reanalysis conflict、bundle download、STOMP reconnect和非管理员拒绝。

## Concrete Steps

所有命令从`D:\study\PixFlow`执行。开始或恢复先运行：

    git status --short
    rg --files docs/design-docs/exec-plans | rg -v "completed[\\/]"
    git diff -- docs/design-docs/exec-plans docs/design-docs/frontend pixflow-app pixflow-web

建立当前route/consumer基线：

    rg -n "@(Get|Post|Put|Patch|Delete)Mapping|registerStompEndpoints|/user/queue" pixflow-app/src/main/java --glob "*.java"
    rg -n "(/api/|/ws/|/topic/|/user/queue)" pixflow-web/src --glob "*.ts" --glob "*.vue"
    rg -n "AssetPackage|TaskResultView|TranscriptMessageView|Entity|Mapper" pixflow-app/src/main/java/com/pixflow/app/web --glob "*.java"

每个后端垂直切片先lint/static，再tests：

    mvn -Plinters-audit -pl <owner模块>,pixflow-app -am -DskipTests verify
    mvn -pl <owner模块>,pixflow-app -am test

Conversation/SSE切片：

    mvn -Plinters-audit -pl pixflow-session,pixflow-context,pixflow-loop,pixflow-conversation,pixflow-app -am -DskipTests verify
    mvn -pl pixflow-session,pixflow-context,pixflow-loop,pixflow-conversation,pixflow-app -am test

Materials/Outputs/Activity切片：

    mvn -Plinters-audit -pl pixflow-module-file,pixflow-module-task,pixflow-conversation,pixflow-app -am -DskipTests verify
    mvn -pl pixflow-module-file,pixflow-module-task,pixflow-conversation,pixflow-app -am test

Vision/Download切片：

    mvn -Plinters-audit -pl pixflow-module-vision,pixflow-module-file,pixflow-app -am -DskipTests verify
    mvn -pl pixflow-module-vision,pixflow-module-file,pixflow-app -am test

每个前端切片严格按以下顺序：

    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build

旧路径最终扫描：

    rg -n "includeArchived|archivedConversation|archiveConversation|ConfirmationToken|challenge|Idempotency-Key|idempotencyKey|uploadWholeFile|WholeFileUpload|multipart|ATTACHMENT|tool_call_ready|tool_started|tool_result|toolInput|retriesRemaining" pixflow-app/src pixflow-conversation/src pixflow-web/src --glob "*.java" --glob "*.ts" --glob "*.vue"
    rg -n "(/api/tasks|/api/conversations/.*/tasks|/api/conversations/.*/images|/ws/progress|/topic/task|/topic/packages|/api/rubrics|/api/auth/register)" pixflow-app/src pixflow-web/src --glob "*.java" --glob "*.ts" --glob "*.vue"
    rg -n "minioZipKey|docKey|originalPath|objectKey|candidateKey|sourceResultId|runEpoch|providerPrompt|permissionFacts" pixflow-app/src/main/java/com/pixflow/app/web --glob "*.java"
    rg -n "(/rubrics|/settings|/tasks/:|path: '/files'|HomePage|TaskDetailPage|useRubricsStore|useTask|usePackageProgress)" pixflow-web/src --glob "*.ts" --glob "*.vue"

生产源码和当前Web对这些旧合同预期零命中。测试中的负向字符串可保留在明确命名的removed-route tests；扫描结果必须逐项分类，不能为了空输出删除守护测试。completed docs不在扫描范围。

最终门禁：

    mvn -Plinters-audit -DskipTests verify
    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck
    mvn verify
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build
    git diff --check
    git status --short

真实依赖与App启动按`app-composition-root-completion-refactor-plan.md`使用MySQL 8.4、Redis、MinIO、RocketMQ、Qdrant和fake provider。容器测试必须零skip；Docker不可用时记录实际阻断和首个失败，不得把skipped记为通过或删除测试。

## Validation and Acceptance

Transport验收要求App是唯一controller边界，route inventory与`frontend/api.md`一一对应；owner模块无Spring Web依赖；所有success/failure使用统一envelope；1-based page/filter/sort严格校验；App无mapper/entity/internal import；旧routes不再工作。

Conversation验收要求DELETE真实删除且active turn/task时拒绝；history只有USER/ASSISTANT安全字段且最新页语义正确；message只接受prompt/references；Proposal一次确认幂等；SSE仅七个named events并通过敏感key负向测试；中断按history reconcile，不重放POST。

Materials验收要求Package/Image DTO不含storage/extraction internals；package/SKU/image/search/sort均后端分页；global/package image只返回ORIGINAL；detail含尺寸、preview expiry和previous/next；chunk upload无multipart兼容；reference selectors/exclusions严格且generated不进入PACKAGE/SKU expansion。

Outputs验收要求三层lazy query字段完整；Generated Image拥有独立imageId/referenceKey；Task/Activity清理后group与image仍存在；rename不改identity；delete移除bytes/processable row并更新groups；任何response不含resultId/object location/epoch/provider payload。

Activity验收要求snapshot+cursor、singleuser STOMP、frame sequence、reconnect reconciliation和outbox replay均有测试；Web只按allowedActions展示并用opaque activityId发命令；retry创建/返回同一direct Derived Retry child；clear不删Generated Image。

Vision/Download验收要求EXPIRED不出wire；facts status/version/writer/failure语义与目标一致；PUT/reanalysis conflict和幂等行为固定；bundle只接受canonical IMAGE keys、稳定dedupe/sanitize filename并能混合Original/Generated Image。

Web验收要求没有compatibility normalizer、direct Task API、task/package topic、Rubrics/Settings/Home/Task detail/combined Files；Activity runtime在应用scope；Materials/Outputs分离；Agent timeline不保存raw tool数据；所有wire payload经runtime schema验证；access token/Proposal/draft/presigned URL不持久化。

端到端验收以管理员可观察行为为准：完整upload/chat/proposal/activity/output链工作，清Task后output仍可mention/download，删除Conversation不删素材，删除Generated Image才使其不可处理；非管理员和旧接口都无法访问。只有这些行为、静态门禁、前后端tests/build、真实依赖故障矩阵全部通过，才能勾选本计划并回写父计划Milestone 7。

## Idempotence and Recovery

搜索、lint、compile、test、route inventory、read-model reconciliation和GET查询可重复执行。Output publication按source task-result identity幂等，Task completion snapshot update按task identity/revision幂等；Activity source revision/outbox sequence保持现有幂等；Proposal以proposalId幂等；Vision reanalysis以当前generation+requestId幂等；bundle重复调用只产生新的短期下载handle，不改变asset facts。

数据库使用开发期fresh baseline。执行任何drop/clean前确认目标是本地或测试库并导出有价值数据；未知共享库立即停止。不要加入old column reader、nullable fallback、compatibility view或baselineOnMigrate绕过。Outputs新投影若中途失败，以Generated Image publication identity和owner completion event重放/对账恢复，不回读已清理Task作为长期事实源。

每个里程碑以纵向可运行切片推进：先失败合同测试，再owner API与schema，再App projection，再Web consumer，最后删除旧代码并跑门禁。中途停止时拆分Progress的已完成与剩余，不让同一停止点同时存在两套production route。测试fake必须显式注入，不用Noop或`@ConditionalOnBean`让完整App静默少装。

不得执行`git reset --hard`、`git checkout --`、整仓restore或删除用户工作。当前工作树已有Rubrics/Task/App/docs修改；同文件冲突时阅读diff并在其上最小修改。无法安全合并时记录具体owner seam和等待顺序，不覆盖用户内容。

## Artifacts and Notes

目标调用链：

    Browser HTTP/SSE/STOMP
      -> App wire request validation and safe projector
      -> owner public command/query/event
      -> owner domain and persistence

目标Generated Image/Outputs链：

    Task fenced result
      -> Task GeneratedAssetPublicationPort candidate
      -> App enriches Conversation title snapshot
      -> File idempotent Generated Image publication
      -> File persistent Outputs conversation/task/image read model
      -> App OutputController
      -> Web Outputs store/page

目标Agent event链：

    Loop typed internal event + Conversation typed Proposal publication
      -> App AgentTurnEventProjector allowlist
      -> seven safe SSE events
      -> Web schema validation and reducer

目标Activity链：

    File/Task owner snapshot and events
      -> App persistent projection/outbox with hidden command target
      -> REST snapshot + one user STOMP queue
      -> opaque activityId commands
      -> File/Task owner recheck and CAS

明确禁止：

    App controller -X-> mapper/entity/internal/persistence/runtime
    Browser -X-> Task aggregate/result rows
    Browser -X-> object/storage/provider/permission/trace facts
    Task cleanup -X-> Generated Image/Outputs
    SSE projector -X-> arbitrary map/reflection passthrough
    Web -X-> parse referenceKey/activityId/display path as identity

## Interfaces and Dependencies

Conversation public lifecycle应提供语义等价接口：

    public interface ConversationService {
        ConversationView create(long administratorId, CreateConversationCommand command);
        PageResponse<ConversationView> list(long administratorId, PageRequest page);
        ConversationView get(long administratorId, String conversationId);
        void delete(long administratorId, String conversationId);
    }

    public record ConversationView(
        String conversationId, String title, Instant createdAt, Instant updatedAt) {}

History view只能包含目标字段。Deletion guard由Conversation定义、App用Task实现：

    public interface ConversationDeletionGuard {
        void requireDeletable(long administratorId, String conversationId);
    }

File Materials/Outputs public queries应使用typed query objects，不暴露mapper condition。语义等价接口：

    public interface MaterialQueryService {
        PageResponse<MaterialPackageView> listPackages(MaterialPackageQuery query);
        MaterialPackageDetail getPackage(long packageId);
        PageResponse<MaterialSkuView> listSkus(long packageId, PageRequest page);
        PageResponse<OriginalImageView> listOriginalImages(OriginalImageQuery query);
        OriginalImageDetail getOriginalImage(long packageId, long imageId);
    }

    public interface OutputQueryService {
        PageResponse<OutputConversationView> listConversations(OutputConversationQuery query);
        PageResponse<OutputTaskView> listTasks(String conversationId, PageRequest page);
        PageResponse<GeneratedImageView> listImages(String taskId, PageRequest page);
    }

实际record名称可按File context调整，但字段必须足以投影目标DTO且不含ObjectLocation。Preview capability返回URL+expiry；width/height/content metadata是owner facts。

Generated Image publication需要新增不可变output context，但不能让Task依赖Conversation：

    public record GeneratedOutputContext(
        String conversationId,
        String conversationTitleSnapshot,
        String taskId,
        OutputTaskType taskType,
        Instant taskCreatedAt) {}

Task candidate提供除title之外的事实；App查询Conversation并构造该context交给File。Task terminal event调用File-owned：

    public interface GeneratedOutputLifecycle {
        void markTaskFinished(String taskId, Instant finishedAt);
    }

Activity controller不消费隐藏source。App application service语义等价于：

    public interface ActivityApplicationService {
        ActivityPage list(long administratorId, ActivityQuery query);
        ActivityView get(long administratorId, String activityId);
        void cancel(AuthPrincipal principal, String activityId);
        RetryActivityResult retryFailed(AuthPrincipal principal, String activityId);
        void clear(AuthPrincipal principal, String activityId);
    }

SSE wire payload使用sealed interface/records或等价强类型实现；不得使用`Map<String,Object>`作为公开payload，也不得用reflection自动复制internal event。内部AgentEvent可以继续承载trace，但projector只能读取每种允许事件所需字段。

依赖方向保持：owner modules不依赖App；App依赖owner public APIs并显式装配cross-context adapters；`pixflow-contracts`只放真正中立的Asset Reference等纯形状，不成为wire DTO仓库。`pixflow-web`只依赖JSON合同，不了解Java owner types。

## Revision Notes

2026-07-21 / Codex: 完成 Milestone 0-6 的生产代码与 Web 单向迁移，并补齐 route inventory、strict HTTP JSON、typed Proposal SSE、Stop/history reconciliation、application-scoped Agent queue 与 reference-only 消息。记录最终前端 25 files/122 tests、App 目标 23 tests 通过；Milestone 7 因 Docker 不可用导致容器测试 skipped/失败且真实依赖 E2E 未运行，保持未完成。

2026-07-21 / Codex: 创建本专项中文ExecPlan。基于权威`frontend/api.md`、父后端架构计划、App组合根计划、相关领域设计及当前源码，逐模块审计旧接口并固定一次性迁移方案。新增关键决定：owner API与wire DTO分离；Conversation real delete/history safe view；七类白名单SSE与显式Proposal事件；File-owned持久Outputs read model；Activity opaque command target；Vision recovery-state projection；前端删除Task/archive/multipart/progress/Rubrics兼容面。本次只新增计划文件，未修改生产代码或既有脏文件，未运行构建测试。
