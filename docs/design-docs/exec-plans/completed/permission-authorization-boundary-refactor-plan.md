# 将 Permission 重构为面向管理员、运行时范围与资源证明的 deny-first 授权边界

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。执行者只依赖当前工作树和本文，就应能把 `pixflow-permission` 从确认令牌闸门重构为 2026-07-17 目标设计中的 deny-first 授权边界。本文是 `docs/design-docs/exec-plans/backend-architecture-alignment-refactor-plan.md` Milestone 2 的 Permission 专项子计划；总计划决定跨模块阶段顺序，本文补足 Permission 的机制、接口、提交切片、验证方法和快速检索入口。每个停止点都必须更新本文，任何接口归属、依赖方向、错误语义或实施顺序变化都必须记录到 `Decision Log` 与文末 `Revision Notes`。

## Purpose / Big Picture

完成本计划后，Permission 不再签发、保存或消费确认令牌，也不再返回“需要确认”这种业务流程状态。它只回答一个安全问题：当前请求中已经认证、并且仍符合配置管理员资格的主体，是否能在当前 Main Agent、Plan mode、Explore child 或内部运行范围内，对当前 Conversation、canonical Asset Reference、Proposal 或 Task 执行指定动作。

用户仍然只需对一个已完整校验的 Proposal 点击一次确认。确认不是令牌交换，而是 Conversation 的直接命令：后端重新检查管理员资格、会话归属、资源当前状态、payload hash 和 pending CAS，再以 `proposalId` 作为业务幂等键创建或返回同一 Task。Plan mode、Explore child、内部压缩运行时、伪造 `referenceKey`、已删除素材、其他会话的 Proposal 或 Task，即使通过历史 prompt、隐藏 UI 或直接调用 handler 进入执行入口，也会被 Java 权限代码拒绝。

开发者可以观察这一变化：Permission 单元测试将由“无 token 返回 `CONFIRM_REQUIRED`、BULK token 可消费一次”改为“缺少认证或任一可信证明即拒绝、显式 deny 永远胜出、正常 Main Agent 只在满足动作证明后允许”；Tools 集成测试证明隐藏工具的幻觉调用仍在 handler 前被拒绝；Conversation 集成测试证明一次 confirm 创建一个 Task、重放返回同一 Task；全仓负向搜索不再命中 `ConfirmationTokenService`、`ConfirmationTokenStore`、`ConfirmationLevel`、`TokenClaims`、`pendingToken` 或 `BULK_CONFIRMATION_REQUIRED`。

## Progress

- [x] (2026-07-17) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、三个活动 ExecPlan、`CONTEXT-MAP.md`、Permission/Auth/Tools/Loop/Hooks/Conversation/File/Task/Contracts 目标设计、Permission 参考架构与 ADR 0007。
- [x] (2026-07-17) 对比最新提交 `0faa171` 与父提交 `b620027`，确认 `docs/design-docs/infra/permission.md` 从确认令牌设计收敛为 direct-confirm、deny-first 授权设计，变更量为 111 行新增、408 行删除。
- [x] (2026-07-17) 审计当前 Permission 代码及直接消费者，确认旧令牌模型仍存在于 Permission、Contracts、Conversation、Tools、Loop 与 Web 类型中；Cache 的 Redis token adapter 正由活动基础设施计划删除。
- [x] (2026-07-17) 运行旧基线 `mvn -pl pixflow-permission -am test`，结果为 `BUILD SUCCESS`；Common 23 个测试、Permission 6 个测试通过，无失败或跳过。
- [x] (2026-07-17) 创建本中文专项 ExecPlan，明确机制、模块接缝、微提交、验收和快速检索关键词。
- [x] (2026-07-17) 根据用户补充要求把迁移收紧为 breaking refactor：一个原子提交同步切换生产方与全部消费者，并删除旧 token/contracts/Web 路径，不保留任何兼容层。
- [x] (2026-07-17) Milestone 0：冻结 Permission-owned principal/scope/plan/subject、五个 proof ports 和禁止反向依赖扫描。
- [x] (2026-07-17) Milestone 1：以固定顺序 deny-first policy、两态 decision 和八类终态错误替换 token/BULK/三态内核；Permission 12 tests passed，其中 ApplicationContext 测试证明五个 required proof 缺任一项都会启动失败。
- [x] (2026-07-17) Milestone 2：Loop 显式传递可信 principal/runtime/Plan mode；Tools 在 handler 前授权、hook 改写后重授权，并在属主深校验后使用服务端注入的最小能力再次授权 Proposal publication。
- [x] (2026-07-17) Milestone 3：接入 Auth/File/Conversation/Task 当前事实 proof，切换空 body direct confirm/reject，补齐 ephemeral Proposal、Task proposalId 幂等 replay 和并发 CAS；删除 confirmation contracts、pending-plan 表与 Web challenge/token 路径。
- [x] (2026-07-17 19:06+08:00) Milestone 4 的 Permission 基础设施子范围：App proof adapters、required-proof ApplicationContext、并发 confirm、负向扫描、触达模块 verify、前端验收和属主 proof fault-isolation 矩阵已完成。新增测试证明 Auth、Conversation、Asset、Proposal 任一当前事实不可用时 Proposal 保持 `PENDING` 且 Task 创建次数为零，Task proof 不可用时 durable replay 不创建第二个 Task；File 与 Conversation concrete adapters 的依赖异常都映射为 `UNAVAILABLE`。canonical Asset Reference owner namespace 与完整 tool capability catalog 仍属于总计划 Milestone 1/7，不在本基础设施子范围虚报完成。

## Surprises & Discoveries

- Observation: 最新 Permission 设计不是旧实现的接口重命名，而是安全机制替换。
  Evidence: `docs/design-docs/infra/permission.md` 明确系统没有 confirmation token、challenge、fixed phrase、BULK level 或 token store；现有 `DefaultPermissionPolicy` 却仍以 `ConfirmationTokenService` 和 `bulkThreshold` 为构造依赖，并在无 token 时返回 `CONFIRM_REQUIRED`。

- Observation: 旧 Permission 单元测试全部通过，但通过只证明旧机制内部一致，不能证明符合当前目标设计。
  Evidence: 2026-07-17 运行 `mvn -pl pixflow-permission -am test`，Permission 6 个测试通过；测试源码仍构造 `TokenClaims`、`ConfirmationToken`、`ConfirmationLevel.NORMAL` 并断言 `PermissionAction.CONFIRM_REQUIRED`。

- Observation: 当前 `PermissionContext` 没有认证主体、管理员资格、runtime scope 或 Plan mode，只带 conversationId、pending token、subagent 和动态工具集合。
  Evidence: `pixflow-permission/src/main/java/com/pixflow/harness/permission/PermissionContext.java`；`pixflow-loop/.../DefaultPermissionContextFactory.java` 当前把 `RuntimeState.metadata` 翻译成旧上下文，并把 token 固定为 null。

- Observation: 仓库中已有三个不同层次的 runtime/Plan 类型，不能让 Permission 随意依赖其中任一实现类型。
  Evidence: Hooks 有 `com.pixflow.harness.hooks.payload.RuntimeScope`，Eval 有同名枚举，Agent 有 `PlanModeState`，Tools 有 `PlanModeView`。Permission 若直接 import Agent 或 Eval 类型会破坏底层边界。

- Observation: 当前 Auth 有 `AuthPrincipal` 和 Spring Security context，但尚未提供目标设计所需的、每个边界都可调用的 configured-administrator eligibility 窄接口。
  Evidence: `pixflow-infra-auth/.../AuthPrincipal.java` 与 `AuthContextHolder.java` 已存在；`docs/design-docs/infra/auth.md` 要求 login、refresh、`/me` 和每个 JWT 请求重新检查配置管理员资格。Permission 不能把 JWT role、前端标志或 tool input 当作资格证明。

- Observation: Permission 令牌删除横跨多个模块，单独先删除 `ConfirmationTokenService` 会使 Conversation 自动配置和确认服务无法编译。
  Evidence: `pixflow-conversation/.../ConfirmationService.java` 与 `ConversationAutoConfiguration.java` 直接依赖该服务；Contracts 仍有完整 confirmation package；Web 仍声明 `ConfirmationToken`。

- Observation: 工作区已有大量用户改动，尤其 Cache token adapter 删除和 Lint 修复正在进行。
  Evidence: `git status --short` 显示 `pixflow-infra-cache`、Common、File、Storage、Vector、MQ、Checkstyle 与多个计划存在未提交改动。本计划不得覆盖、移动或回滚这些文件；Cache confirmation 清理由 `small-infrastructure-alignment-refactor-plan.md` 负责。

- Observation: Milestone 3 声明的 Conversation ephemeral Proposal 和 Task proposalId replay 前置能力在代码中尚不存在，若只接 proof adapter 会留下不可运行的 direct-confirm 路径。
  Evidence: 基线仍以 `pending_plan` 表持久化未确认内容，Task API 也没有 `findByIdempotencyKey`。本批次因此补齐进程内 Proposal store、CAS、Task durable replay，并删除表迁移与 MyBatis 依赖。

- Observation: Windows Maven 增量编译会掩盖跨模块签名变化，单模块 `clean test` 才能可靠验证当前源码。
  Evidence: Permission、Tools、DAG、Imagegen、Conversation 均使用 clean test 重新编译；Imagegen 首次 reactor test 还揭示了并行 Storage 改动新增 `ObjectStorage.copy` 后测试 stub 未同步的问题。

- Observation: 全量 Loop 测试当前存在与 Permission 重构无关的失败。
  Evidence: `AgentLoopContinuationDecisionTest.executesToolCallsWhenModelRequestsToolAndCompletesOnNextRound` 因缺少预期 tool protocol 报 `NoSuchElementException`；`RuntimeScopeTranslatorTest.subagentEvalMapsToSubagentHooks` 的期望 `sub_agent` 与实际 `subagent` 不一致。Permission context factory 5 个测试通过。

- Observation: 完成后的双轴 code review 揭示了三个会被普通 happy-path 测试掩盖的授权缺口。
  Evidence: durable replay 原先只证明 URL conversation、未证明查出的 Task 属于该 conversation；第二个并发 confirm 会在 `CONFIRMING` 状态提前失败；Proposal payload hash 原先只做存储字段自比较。现已分别改为 `TaskCommand(CONFIRM_REPLAY)` 当前事实证明、允许 `PENDING/CONFIRMING` 加入同一 completion signal，以及从 DAG canonical bytes / Imagegen plan 重新计算 hash 后常量时间比较。

- Observation: 工具可见性与运行上下文默认值同样属于安全边界，而不只是 UI 或便利默认。
  Evidence: review 发现按工具名前缀猜只读能力、缺 runtime scope 默认 Main、缺 Plan view 默认 OFF 会把“不知道”提升为 allow。实现现改为使用 `ToolDescriptor.readOnlyHint()`，并让缺 scope/Plan mode 在 policy 中 fail closed；对应 Permission 可见性回归测试已加入。

- Observation: 最终 review 仍确认两个依赖上游领域模型的缺口，不能在本计划内用猜测补齐。
  Evidence: File 当前是单管理员且 Asset 实体没有 owner namespace 事实，`AssetPermissionProof` 只能验证 canonical grammar、package/image/SKU 关联、删除状态和处理就绪；Tools descriptor 也只有 `readOnlyHint`，没有 direct execution / proposal publication / auth mutation capability 或 Explore allowlist。Permission 基础设施 Milestone 4 已完成当前 proof/fault-isolation 责任；canonical Asset Reference 与 capability catalog 的完整业务接线由总计划 Milestone 1/7 继续。未知 Proposal 类型静默降级为 DAG 的独立 fail-open 已改成显式拒绝并补回归测试。

- Observation: 先前阻断 Permission reactor 的 Loop 两项失败已经不再是当前首个组合阻断；本轮完整触达 reactor 更早失败于 Eval 的既有 trace 测试，但 Permission/File/Conversation 可独立全绿。
  Evidence: `mvn -pl pixflow-permission,pixflow-tools,pixflow-module-file,pixflow-conversation -am test` 失败于 `TraceRecorderTest.recordsOpenAndCommittedTurnWithoutLeakingSecrets:39` 的空 `Optional`；随后 `mvn -pl pixflow-module-file,pixflow-conversation test` 得到 File 33、Conversation 40 项测试全部通过。

- Observation: 全依赖严格 verify 当前还会暴露活动 Lint 计划范围内的 Loop 新违规，但不影响本计划目标模块自身的严格门禁结论。
  Evidence: `-am -DskipTests verify` 在 `pixflow-loop/RuntimeState.java` 报 4 条 `EmptyLineSeparator` 和 1 条 `MethodName`；不带 `-am` 对 Permission、八个 infra、Tools、File、Conversation、App 共 13 模块运行严格 verify 全部成功，零 Checkstyle/SpotBugs。

## Decision Log

- Decision: Permission 定义自己的最小可信视图 `PermissionPrincipal`、`PermissionRuntimeScope` 和 `PermissionPlanMode`，上层适配现有 Auth/Hooks/Agent 类型，不让 Permission import Spring Security、Agent、Loop、Hooks 或 Eval 实现类型。
  Rationale: Permission 是低层安全边界。自有值对象可稳定表达授权所需事实，也消除仓库中同名 runtime 类型造成的依赖倒挂。`PermissionPrincipal` 只含不可变 `userId` 与 `username`，不携带可伪造 role 或预先算好的 allowed 标志。
  Date/Author: 2026-07-17 / Codex

- Decision: Permission-owned 窄端口分别验证管理员资格、Conversation、Asset、Proposal 与 Task 证明，生产适配器由属主模块或 `pixflow-app` 提供；`safeFacts` 不能承载 ownership、storage key 或 `allowed=true`。
  Rationale: 策略必须主动获取可信当前事实并在依赖不可用时 fail closed，同时不能依赖 File/Conversation/Task 的实体、mapper 或 service internals。端口倒置让属主保持数据解释权，App 只负责接线。
  Date/Author: 2026-07-17 / Codex

- Decision: `PermissionDecision` 只保留 `ALLOW` 与 `DENY`；删除 `CONFIRM_REQUIRED`。Proposal 等待用户决定是 Conversation 业务状态，不是权限状态。
  Rationale: 新设计的一次确认由 direct endpoint 表达。继续保留第三态会诱导 Tools/Loop 恢复 token/challenge 流程，并混淆“尚未确认”和“当前动作无权执行”。
  Date/Author: 2026-07-17 / Codex

- Decision: deny-first 的固定顺序为 principal 存在、管理员资格、runtime scope、Plan mode、tool/action 分类、Conversation 证明、Asset 证明、Proposal/Task 证明，最后才 allow；任何 required proof 缺失、超时、异常或 unavailable 都映射为对应终态 Permission deny。
  Rationale: 顺序与 `docs/design-docs/infra/permission.md` 完全一致，先执行便宜且全局的拒绝，再读取属主事实。显式 deny 与证明缺失永远不能被后续 allow 翻转。
  Date/Author: 2026-07-17 / Codex

- Decision: `isToolVisible` 继续作为 UX 预过滤能力存在，但只使用可信 context、工具能力目录、runtime scope 与 Plan mode；真正执行始终重新构造完整 `PermissionSubject` 并调用 `evaluate`。
  Rationale: prompt/schema 隐藏能减少模型误调用，但不是安全边界。资源、所属权和输入相关判断只有在调用发生后才有完整事实。
  Date/Author: 2026-07-17 / Codex

- Decision: 令牌相关生产类型、测试、配置、错误码、Contracts、Web 类型和 suppression 在所有消费者迁移后的同一阶段彻底删除，不保留 deprecated adapter 或兼容重载。
  Rationale: 仓库处于开发期；双轨会产生两个确认真相源。允许在一个未提交的工作树中同步编辑生产方和消费者，但任何实施提交结束时都不得同时存在 token-confirm 与 direct-confirm 两条生产路径。切换提交必须同时加入新合同、迁移消费者并删除旧合同。
  Date/Author: 2026-07-17 / Codex

- Decision: 本计划采用破坏性一次切换，不提供 deprecated 类型、旧构造器、旧参数顺序重载、配置别名、token-to-proof adapter、双读双写、feature flag 或 fallback。
  Rationale: 用户明确要求这是重构，不为兼容保留旧代码。仓库仍处于开发阶段，调用方必须在同一切换中迁移；编译失败应通过修正真实消费者解决，不能通过恢复旧 API 解决。
  Date/Author: 2026-07-17 / Codex

- Decision: Proposal 发布的二次授权由 Tools 在每次 `ToolInvocation` 中注入 `ProposalPublicationAuthorizer` 最小能力，属主模块在深校验、canonical references 和 payload hash 确定后调用；缺能力直接 fail closed。
  Rationale: principal、runtime scope 与 Plan mode 只来自执行器当前可信上下文，不能经 tool arguments、metadata 或 Contracts DTO 传播；最小能力也避免 DAG/Imagegen 持有完整 policy/context。
  Date/Author: 2026-07-17 / Codex

- Decision: 并发 confirm 使用 Proposal CAS 选出唯一 Task 创建者，竞争请求持有同一个完成信号并返回相同 taskId；完成后的网络重放从 Task durable idempotency fact 查询。
  Rationale: 这保持未确认 Proposal ephemeral，同时把幂等真相放在 Task，而不是恢复 single-use token 或持久 pending 表。
  Date/Author: 2026-07-17 / Codex

- Decision: durable confirm replay 必须作为 `TaskCommandType.CONFIRM_REPLAY` 授权，由 Task proof 以 `taskId + conversationId` 查询当前事实；不能用普通 ToolInvocation 只证明 URL conversation。
  Rationale: idempotency key 是定位 Task 的手段，不是 Task 属于当前会话的授权证据。独立 Task subject 可阻断跨会话 taskId 泄漏，并继续复用 deny-first 顺序。
  Date/Author: 2026-07-17 / Codex

- Decision: Proposal proof 允许 `PENDING` 与 `CONFIRMING`，但不允许 `CONFIRMED`；`CONFIRMING` 请求只能加入 repository 中同一个完成信号，不能创建第二个 Task。
  Rationale: 授权状态和 CAS ownership 是两个接缝。先到请求通过 CAS 成为 owner，后到请求仍需通过当前事实授权，再以 completion claim 等待同一幂等结果。
  Date/Author: 2026-07-17 / Codex

## Outcomes & Retrospective

生产重构已完成原子切换。Permission 现在保留 `PermissionPrincipal`、`PermissionRuntimeScope`、`PermissionPlanMode`、sealed `PermissionSubject`、两态 `PermissionDecision` 和五个 proof ports；不再依赖 Contracts confirmation、Cache、业务模块或 token store。Auth/Task proof 位于 App，Asset proof 位于 File，Conversation/Proposal proof 位于 Conversation。Proposal publication 在 Tools 注入的可信能力处二次评估，confirmation 会重查 reference、pending/hash，并以 CAS + Task `proposal:{proposalId}` 幂等事实处理并发和重放。

验证证据更新为：Permission 12、File 33、Conversation 40 项测试通过；新增 fault-isolation 测试覆盖 Administrator、Conversation、Asset、Proposal 和 Task 五个 proof 位置，所有 confirm/replay 故障都在 Task 创建前 fail closed。Tools 入口 3 项与 App proof adapter 4 项定向测试继续证明授权拒绝时 handler/publication side effect 不执行、concrete adapters fail closed。八个 infra 模块与 Permission 的统一 reactor 在真实 Docker 环境中 `BUILD SUCCESS`，Redis、MinIO、Qdrant 用例零跳过；Permission、八个 infra、Tools、File、Conversation、App 共 13 个目标模块的直接严格 verify 全部零 Checkstyle/SpotBugs。完整触达 reactor 仍未声明通过，因为范围外 Eval trace 测试失败，`-am` 严格 reactor 又被活动 Lint 计划范围内的 Loop 5 条违规阻断；本计划未越界修改它们。canonical Asset Reference owner namespace 和 tool capability catalog 的业务接线留给总计划 Milestone 1/7。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。Permission Maven 模块目录是 `pixflow-permission`，Java 包目前为 `com.pixflow.harness.permission`；虽然物理模块名没有 `infra` 前缀，它在总体架构中承担基础设施安全边界。`pixflow-infra-auth` 认证请求并产生 `AuthPrincipal`；`pixflow-tools` 在 handler 前调用 Permission；`pixflow-loop` 创建每回合可信上下文；`pixflow-conversation` 拥有 Proposal 生命周期与 confirm/reject；`pixflow-module-file` 解析 canonical Asset Reference 并证明资源状态；`pixflow-module-task` 拥有 Task 命令和幂等创建；`pixflow-app` 是组合根，负责把这些模块的公开端口接成运行系统。

本计划使用以下术语。Principal 是服务端从已验证请求得到的主体，不是请求 body 或模型输入里的 userId。Configured administrator eligibility 是 Auth 根据当前配置、数据库账户和请求边界重新判断该 principal 仍是唯一允许管理员的事实。Runtime scope 表示代码运行在 Main Agent、Explore child 或内部总结/压缩范围。Plan mode 是 Main Agent 的只读规划状态，处于该状态时禁止 Proposal 发布和其他副作用。Canonical Asset Reference 是后端生成的 `referenceKey`，标识 PACKAGE、SKU 或 IMAGE；对象存储 key、展示路径和模型声称的 ownership 都不是授权输入。Proof port 是 Permission 定义的窄接口，生产适配器查询属主模块的当前事实并返回可验证结论；缺少结论等同拒绝。

开始任何里程碑前，执行者必须重读 `AGENTS.md`、本计划、当时 `docs/design-docs/exec-plans/` 下全部活动计划、`docs/design-docs/index.md`、`docs/design-docs/design.md`、`CONTEXT-MAP.md`、`docs/design-docs/infra/permission.md` 和 `docs/design-docs/infra/auth.md`。涉及 Tools/Loop 时读相应 harness 文档；涉及 Proposal 时读 `docs/adr/0007-keep-unconfirmed-proposals-ephemeral.md`、Conversation 设计与 `pixflow-conversation/CONTEXT.md`；涉及资源或 Task 时读 File/Task 设计及现有 Context 文档。最新 accepted ADR 与目标设计优先于 completed ExecPlan；`completed/permission-module-implementation-plan.md` 只用于定位旧代码，不能作为目标规范。

## Scope and Non-Goals

范围包括 `pixflow-permission` 的模型、策略、错误码、自动配置和测试；Permission 所需的 Auth-facing adapter、Loop context factory、Tools subject adapter、Hooks 只读 decision view、File/Conversation/Task proof adapters、App 接线；以及为彻底删除旧机制所必需的 Contracts、Conversation、Web 类型与 Checkstyle 精确 suppression 清理。

本计划不实现多租户、组织 RBAC、可编辑规则 DSL、目录/命令授权、用户角色管理、审计数据库、持久权限 grant、令牌替代物或第二次批量确认。它不负责完整实现 Auth 的管理员登录重构、File canonical reference 数据模型、Conversation ephemeral Proposal store 或 Task 创建语义；这些由后端总计划相应里程碑拥有。但本计划必须定义和验证 Permission 的接缝，并在这些前置能力就绪的同一实施批次完成集成，不能留下 production allow-all fake。

迁移策略是明确的 breaking refactor。最终代码以及任一实施提交的生产代码都只能有新机制；不能以“后续再删”为理由保留旧 token service、旧 action、旧 context 字段、旧 error code、旧 configuration property、旧 Web DTO 或适配层。为了完成原子切换，可以在提交前的本地工作树中同时编辑多个模块，但提交前必须完成全部消费者迁移和负向搜索。

## Plan of Work

### Milestone 0：冻结最小授权语言与可信证明端口

先保存 `git status --short`、Permission 与消费者 diff、旧 token 全仓命中和定向测试结果。不要修改当前 Cache、Common、File、Storage、Vector、MQ 或 Lint 工作；若本计划需要删除 `ConfirmationTokenService.java` 的精确 Checkstyle suppression，应只删除该一条，并与文件删除放在同一提交。

在 `pixflow-permission` 先用测试冻结新公共模型。`PermissionPrincipal` 表达 userId/username；`PermissionRuntimeScope` 至少表达 `MAIN`、`EXPLORE_CHILD`、`INTERNAL`；`PermissionPlanMode` 表达 `OFF` 与 `ACTIVE`。`PermissionSubject` 改为 sealed hierarchy：`ToolInvocation`、`AssetAccess`、`ProposalPublication`、`ProposalConfirmation` 和 `TaskCommand`。构造器拒绝空 tool/proposal/task id、空 payload hash、空或非 canonical reference list；`safeFacts` 做不可变防御拷贝，并明确拒绝保留键 `userId`、`username`、`ownerId`、`storageKey`、`allowed`。

在 Permission 中定义窄 proof ports：`AdministratorEligibilityPort`、`ConversationAuthorizationPort`、`AssetAuthorizationPort`、`ProposalAuthorizationPort`、`TaskAuthorizationPort`。每个端口返回明确的 `PROVED`、`DENIED` 或 `UNAVAILABLE`，不得只返回含义模糊的 null；异常在 policy 边界归一为 `UNAVAILABLE` 并 fail closed。端口参数只使用 Permission-owned 值对象，不泄漏属主实体。为依赖方向增加 ArchUnit、Maven Enforcer 或稳定的源码扫描测试，禁止 Permission 生产代码 import `com.pixflow.contracts.confirmation`、Spring Security、Redis、File/Conversation/Task 内部包、Agent、Loop、Tools、Hooks 或 Eval。

里程碑完成时，新合同和架构测试应已写好，但不得单独提交一套与旧 token policy 并存的生产 API。若接口替换导致旧类无法编译，就继续在同一未提交工作树中完成 policy、消费者和删除项，直到一次性切换提交可构建；不制造中间 broken commit，也不制造双机制 commit。

### Milestone 1：以固定顺序实现 deny-first policy

重写 `DefaultPermissionPolicy`，删除 `ConfirmationTokenService`、`bulkThreshold` 和 token store 依赖。评估入口只保留 `evaluate(PermissionContext context, PermissionSubject subject)`；所有调用点同步修改，不提供旧参数顺序重载或任何临时 compatibility adapter。

策略先验证 context principal，再调用 `AdministratorEligibilityPort`。随后执行 runtime scope 和 Plan mode 规则：Main normal 可进入动作分类；Main Plan 只允许显式只读 search/read/inspection 与 plan exit；Explore child 只允许白名单只读工具，禁止 Proposal、Task command 和递归 spawn；Internal 默认没有业务工具，除非运行时能力目录明确允许只读必要项。接着检查 tool/action 分类。只有这些全局检查通过，才按 subject 类型请求 Conversation、Asset、Proposal 或 Task proof。一个 subject 需要多项证明时全部成功才 allow，例如 Proposal confirmation 同时需要 conversation、proposal current state/hash 与引用资源可用证明。

把 `PermissionAction` 收敛为 `ALLOW`、`DENY`，或用 sealed decision 表达同样两态。`PermissionDecision` 只保留安全的 reason category、source 和不含身份/路径/凭证的 metadata。错误码替换为设计指定的八类：`PERMISSION_UNAUTHENTICATED`、`PERMISSION_ADMIN_INELIGIBLE`、`PERMISSION_SCOPE_DENIED`、`PERMISSION_PLAN_MODE_DENIED`、`PERMISSION_CONVERSATION_DENIED`、`PERMISSION_ASSET_DENIED`、`PERMISSION_PROPOSAL_DENIED`、`PERMISSION_TASK_DENIED`。所有错误为终态 Permission 类错误，经 Common sanitizer 后才能发往 client/model。

单元测试采用 fake proof ports，逐项证明拒绝顺序。测试必须覆盖：显式 scope deny 不调用后续资产端口；管理员 adapter unavailable 时拒绝；Plan mode 的伪造 side-effect 调用拒绝；Explore child 的 read-only 白名单允许、非只读拒绝；错误 kind/deleted/unavailable reference 拒绝；Proposal hash 或 pending state 不匹配拒绝；Task 不属于当前 conversation 拒绝；所有证明成功才 allow；先前 deny 不会被后续 proof 翻转。

### Milestone 2：接入 Loop、Tools、Hooks 与 runtime 可见性

修改 `DefaultPermissionContextFactory`，不再从任意 metadata 拼接权限身份。Principal 必须由服务端 authenticated request/turn admission 注入的可信 accessor 转换为 `PermissionPrincipal`；runtime scope 从 `RuntimeState.runtimeScope()` 显式映射；Plan mode 从当前 `PlanModeView` 或等价可信状态映射；conversationId 与 toolCallId 来自正在执行的 turn/call。缺 principal 不得默认管理员，缺 runtime scope 不得猜测为 Main allow。

Tools 的分类结果构造 typed subject：普通工具为 `ToolInvocation`；`submit_image_plan` 与 `submit_imagegen_plan` 在属主完成 schema、reference、ownership、readiness、count 和 hash 校验后构造 `ProposalPublication`。删除 classification 中的 confirmationAction、packageId 占位与 token 事实。`RegistryToolExecutor` 对 `DENY` 在 handler 前短路；不再识别 `CONFIRM_REQUIRED`。若 hook 改写 input，重新校验、重新分类并重新授权，旧 PermissionDecision 不能沿用。

工具可见性使用与执行相同的 capability catalog。Plan mode 和 Explore child 从 provider schema/prompt 隐藏副作用工具；Main normal 显示符合 runtime 的工具。但增加测试直接构造被隐藏工具的 ToolCall，证明执行入口仍 deny。Hooks 只收到脱敏后的只读 decision view，不能通过 allow 翻转 deny；Loop 只负责构造 context 并传给 Tools，不自己解释具体权限规则。

完成时运行 Permission、Tools、Hooks、Loop 和 Agent 定向测试。对 `PermissionContext` 构造器签名的所有测试 fake 一次性迁移，不能保留一个无 principal 的便利构造器供生产误用。

### Milestone 3：接入属主证明并删除旧确认体系

进入本里程碑前，后端总计划必须已冻结 Auth configured-administrator 接口、File canonical resolver、Conversation ephemeral Proposal API 与 Task idempotent command API。若其中任一未就绪，在 `Progress` 记录具体前置条件并继续完成可独立的 Milestone 0-2；不得用 allow-all adapter 伪造完成。

Auth adapter 每次调用当前资格检查，不信任 JWT role。File adapter 调 public resolver，以 current facts 证明 grammar、kind、owner namespace、deleted/processable/generated availability；对象 key和 display snapshot 不进入 proof。Conversation adapter 证明 conversation 属于当前管理员且 Proposal 位于该 conversation、仍 pending、payload hash 相同。Task adapter 证明 task 归属、当前状态允许 cancel/retry/delete/download 等具体 command。属主依赖不可用均返回 `UNAVAILABLE`，policy 映射为对应 deny，不降级放行。

Conversation direct confirm 按固定顺序执行：认证并重查管理员，读取 ephemeral Proposal，重新检查 conversation、references、payload hash 和 permission，对 pending 做 CAS，以 `proposalId` 调 Task 幂等创建/查找，绑定 taskId 后删除 Proposal。用户 body 为空；没有 token、challenge answer、fixed phrase、BULK level 或客户端 idempotency key。重复 confirm 在 Proposal 已删除时通过 Task 的 `proposalId` lookup 返回原 taskId；重复 reject 成功且无 durable 记录。

消费者迁移完成后删除 `pixflow-permission/.../token/ConfirmationTokenService.java`、测试内存 store、`PermissionProperties.bulkThreshold`（若类不再有其他配置则删除整类）、旧错误码、旧测试和精确 suppression；删除 `pixflow-contracts/.../confirmation/` 整包以及 pending-plan 共享类型；移除 Permission 对 Contracts 的 Maven 依赖；删除 Conversation token 注入、Web `ConfirmationToken` 类型与 token/challenge 字段。Cache adapter 的删除由活动基础设施计划完成，本计划只用负向搜索确认没有残余依赖，不改写用户正在进行的 Cache 工作。

### Milestone 4：组合根、故障注入与全仓验收

在 `pixflow-app` 明确注册所有 proof adapter 和 context principal bridge。增加 ApplicationContext 测试，证明正常组合只有一个 `PermissionPolicy`，不存在 token service/store Bean；缺少任一 required proof adapter 时应用启动失败并明确指出缺失 bean。只在已经成功装配后发生的运行期查询异常才映射为 `UNAVAILABLE` deny，不安装默认、fallback 或 allow-all/deny-all compatibility bean。

增加故障注入测试：Auth/File/Conversation/Task proof adapter 分别超时或抛异常时，handler、task create 和 storage/model side effect 都未发生；重新恢复依赖后新请求可成功，之前失败请求不会产生 grant。并发 confirm 测试应证明两个请求最多创建一个 Task，两个响应返回同一 taskId；这是 Proposal/Task CAS 与幂等机制，不是 Permission token single-use。

最后串行运行相关模块、App、Maven reactor 和前端测试/构建。更新后端总计划 Milestone 2 的 Progress 与交接记录；若 File/Task 其他总计划里程碑尚未完成，只记录其 proof contract 已接线，不虚报完整端到端资产发布完成。

## Tiny Commit Sequence

本次 breaking refactor 的核心只能有一个原子切换提交 P1：同时加入 Permission-owned principal/scope/plan/subject/proof contracts，替换 policy/decision/error/tests，迁移 Loop、Tools、Hooks、Auth/File/Conversation/Task adapters 与 App 接线，切换 direct confirm/reject 和 Task proposalId replay，并删除 Permission token/config/tests/suppression、Contracts confirmation/pending types及 Web token/challenge 类型。这个提交可以较大，因为拆开会迫使仓库保留兼容层或不可构建状态；提交前在同一工作树完成所有修改和定向验证。

P1 之后的提交只增加或强化新机制：P2 增加 proof dependency 故障注入与并发 confirm 测试，P3 增加架构/负向搜索守护和缺 bean 启动测试，P4 更新本计划、后端总计划和实际测试证据。后续提交不得恢复任何旧类型来修复测试。

每个提交至少运行被触达模块的定向测试与 `git diff --check`。不得把当前 Cache、Lint 或 File 用户改动卷入 Permission 提交；发现文件重叠时先记录并串行协调，而不是 restore 或覆盖。

## Concrete Steps

所有命令从仓库根目录执行：

    cd D:\study\PixFlow
    git status --short
    git show -s --format=fuller HEAD
    git diff HEAD^ HEAD --numstat -- docs/design-docs/infra/permission.md

建立旧机制基线：

    rg -n "ConfirmationToken|ConfirmationAction|ConfirmationLevel|TokenClaims|ConfirmationTokenStore|CONFIRM_REQUIRED|BULK_CONFIRMATION|pendingToken|bulkThreshold" pixflow-permission pixflow-contracts pixflow-conversation pixflow-tools pixflow-loop pixflow-web config/checkstyle/suppressions.xml
    mvn -pl pixflow-permission -am test

2026-07-17 的已知基线是 Permission 6 tests passed、Common 23 tests passed、`BUILD SUCCESS`。实施后的第一条搜索在上述生产/测试范围必须无输出；历史 completed 文档可保留旧词，因为它们是历史证据。

验证 Permission 与 harness：

    mvn -pl pixflow-permission,pixflow-tools,pixflow-hooks,pixflow-loop,pixflow-agent -am test
    mvn -pl pixflow-permission,pixflow-tools,pixflow-hooks,pixflow-loop,pixflow-agent -am -DskipTests verify
    rg -n "com\.pixflow\.(contracts\.confirmation|infra\.cache|module\.|agent|harness\.(tools|loop|hooks|eval))" pixflow-permission/src/main/java

最后一条预期无输出。若 proof ports 的生产 adapters 位于 Permission 之外，这是正确结果。

验证 Auth/Conversation/File/Task/App 接缝：

    mvn -pl pixflow-infra-auth,pixflow-module-file,pixflow-conversation,pixflow-module-task,pixflow-app -am test
    mvn -pl pixflow-infra-auth,pixflow-module-file,pixflow-conversation,pixflow-module-task,pixflow-app -am -DskipTests verify
    rg -n "ConfirmationTokenService|ConfirmationTokenStore|ConfirmationLevel|TokenClaims|pendingToken|BULK_CONFIRMATION_REQUIRED" --glob "*.java" --glob "*.xml" --glob "*.yml" --glob "*.yaml" --glob "*.ts" --glob "*.vue" .

全仓验收串行运行，避免并行 Maven 共享 target 文件冲突：

    mvn -DskipTests verify
    mvn verify
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build
    git diff --check
    git status --short

若 Docker 不可用，记录具体跳过的 MySQL/Redis/MinIO 测试类和数量；不得把 integration test 改为 mock 或声称跳过即通过。Permission 纯单元测试、ApplicationContext 测试和 `-DskipTests verify` 仍必须运行。

## Validation and Acceptance

Permission 内核验收要求：无 principal 返回 `PERMISSION_UNAUTHENTICATED`；当前 principal 不再是配置管理员或资格端口不可用返回 `PERMISSION_ADMIN_INELIGIBLE`；Plan mode 的任一副作用返回 `PERMISSION_PLAN_MODE_DENIED`；Explore child 只能调用白名单只读工具；Internal runtime 默认拒绝业务动作；显式 deny 后不调用后续 proof；所有 required proofs 成功才 allow。

资源与业务边界验收要求：非法、错误 kind、已删除或 unavailable `referenceKey` 在 handler/模型/存储副作用前拒绝；其他 conversation 的 Proposal/Task 拒绝；Proposal hash/state 变化拒绝；Task command 必须同时满足 owner 与 state；proof dependency 异常不降级。错误响应不泄漏 userId、storage key、内部路径、payload 内容或资格判断细节。

Proposal 验收要求：发布前已有 schema、canonical references、ownership、readiness、count、hash 和领域事实校验；Main normal 可发布，Plan/child/internal 不可发布。第一次空 body confirm 创建一个 Task；并发或网络重放返回同一 taskId；一次 reject 后 Proposal 消失且不写 durable message/audit；页面或进程丢失 unresolved Proposal 时不会从 token store、Redis 或 pending table 恢复。

架构验收要求：Permission 生产代码只依赖 Common、Spring autoconfigure 与本模块合同，不依赖 Contracts confirmation、Redis、Spring Security 或业务内部包；Cache 不理解确认；Tools/Loop 不含 token；Web 不声明 token/challenge；App 不注册 token Bean。模型可见 schema、prompt、transcript、trace 与 error metadata 都不包含 principal 凭证或内部 ownership facts。

## Idempotence and Recovery

搜索、编译、单元测试和架构扫描可以重复执行。Proof fake 每个测试独立构造，不共享 grant 或 mutable global state。Permission 本身不写数据库或缓存，所以重试一次失败的权限评估不会改变权限；属主模块负责自己的 CAS 与幂等。

删除 confirmation contracts 前必须确认所有生产和测试消费者已经迁移。若迁移中途停止，恢复单位是“新 Permission model/policy + Loop context + Tools subject adapter”；不要留下新 policy 配旧 context，或 Tools 仍判断 `CONFIRM_REQUIRED` 的半迁移。Direct confirm 的恢复单位是“Conversation CAS + Permission recheck + Task proposalId idempotency lookup”；不能先删 token 再保留只会签发 token 的 ConfirmationService。

不得执行 `git reset --hard`、整仓 restore、删除用户未跟踪文件或格式化无关模块。当前 Cache token adapter 删除属于另一个活动计划；本计划只在最终扫描中验证结果。若 `config/checkstyle/suppressions.xml` 同时被 Lint 计划修改，仅删除精确指向已删除 Permission token 文件的 suppression，并与对方串行处理。

## Quick Reference Search Keywords

理解最新提交的机制变化，在 `git diff HEAD^ HEAD -- docs/design-docs/infra/permission.md` 搜索 `Removed mechanisms`、`no confirmation token`、`Evaluation order`、`Proposal boundary`。在后端总计划搜索 `Milestone 2`、`deny-first Permission`、`ephemeral Proposal`、`proposalId`。

定位 Permission 核心机制，在当前设计搜索 `PermissionContext`、`PermissionSubject`、`safeFacts`、`fail closed`、`Runtime restrictions`、`Asset Reference authorization`、`ProposalConfirmation`、`TaskCommand`。定位可借鉴的顺序骨架，在 `docs/references/permission-architecture.md` 搜索 `deny-first 顺序`、`特殊 agent 硬强制`、`Registry 可见性`；只借鉴有序短路和执行入口重判，不复制目录规则、ask/session grant 或 prompter。

定位认证接缝，搜索 `AuthPrincipal`、`AuthContextHolder`、`PIXFLOW_AUTH_ADMIN_USERNAME`、`authenticateAccessToken`、`configured administrator`。定位 runtime/Plan 接缝，搜索 `RuntimeState.runtimeScope`、`DefaultPermissionContextFactory`、`PlanModeView`、`PlanModeState.ACTIVE`、`visibleDescriptors`。

定位资源和业务证明，搜索 `referenceKey`、`Asset Reference Resolver`、`displayPathSnapshot`、`ProposalPublication`、`proposal_ready`、`payloadHash`、`pending CAS`、`proposalId`、`TaskCommandType`、`GeneratedAssetPublicationPort`。定位必须删除的历史机制，搜索 `ConfirmationTokenService`、`ConfirmationTokenStore`、`TokenClaims`、`ConfirmationLevel`、`CONFIRM_REQUIRED`、`BULK_CONFIRMATION_REQUIRED`、`PendingPlanPort`、`RedisConfirmationTokenStore`。

建议直接使用：

    rg -n "PermissionContext|PermissionSubject|PermissionDecision|DefaultPermissionPolicy|DefaultPermissionContextFactory"
    rg -n "AuthPrincipal|AuthContextHolder|PIXFLOW_AUTH_ADMIN_USERNAME|configured administrator"
    rg -n "referenceKey|payloadHash|proposalId|pending CAS|TaskCommandType"
    rg -n "ConfirmationToken|ConfirmationLevel|TokenClaims|CONFIRM_REQUIRED|PendingPlanPort"

## Artifacts and Notes

本计划的对比基线为：

    HEAD  0faa171 docs: align architecture, ADRs, and execution plans
    BASE  b620027 feat(app): expose task assets and progress events
    permission.md  111 insertions, 408 deletions

当前代码基线为：

    pixflow-permission depends on pixflow-common + pixflow-contracts
    DefaultPermissionPolicy constructor requires ConfirmationTokenService + bulkThreshold
    PermissionContext contains conversationId + pendingToken + subagent + denied/disabled tools
    PermissionAction contains ALLOW + DENY + CONFIRM_REQUIRED
    Permission tests: 6 passed on 2026-07-17
    Conversation ConfirmationService still issues and consumes tokens
    Cache RedisConfirmationTokenStore is being removed by another active plan

目标差距不是增加一个 `AuthenticatedPrincipal` 字段，而是把授权证据来源从“一次性 token claims”替换为“每次边界读取当前管理员、scope、conversation、asset、proposal 和 task facts”。因此实现中最重要的测试不是 token 重放，而是 proof unavailable fail-closed、旧 deny 不可翻转、隐藏工具执行入口重判，以及 Proposal confirm 的 CAS/idempotency。

## Interfaces and Dependencies

最终 Permission 公共模型应具有语义等价形状；实际命名可在 Milestone 0 微调，但必须同步更新本文和目标设计：

    public interface PermissionPolicy {
        PermissionDecision evaluate(PermissionContext context, PermissionSubject subject);
        boolean isToolVisible(String toolName, PermissionContext context);
    }

    public record PermissionContext(
        PermissionPrincipal principal,
        PermissionRuntimeScope runtimeScope,
        PermissionPlanMode planMode,
        String conversationId,
        String toolCallId
    ) {}

    public sealed interface PermissionSubject {
        record ToolInvocation(String toolName, boolean readOnly, Map<String,Object> safeFacts)
            implements PermissionSubject {}
        record AssetAccess(String referenceKey, AssetAccessMode mode)
            implements PermissionSubject {}
        record ProposalPublication(String proposalType, List<String> referenceKeys, String payloadHash)
            implements PermissionSubject {}
        record ProposalConfirmation(String proposalId, String payloadHash)
            implements PermissionSubject {}
        record TaskCommand(String taskId, TaskCommandType command)
            implements PermissionSubject {}
    }

Proof ports 应具有语义等价形状：

    interface AdministratorEligibilityPort {
        ProofResult verify(PermissionPrincipal principal);
    }

    interface ConversationAuthorizationPort {
        ProofResult proveAccess(PermissionPrincipal principal, String conversationId);
    }

    interface AssetAuthorizationPort {
        ProofResult proveAccess(PermissionPrincipal principal, String referenceKey, AssetAccessMode mode);
    }

    interface ProposalAuthorizationPort {
        ProofResult provePending(PermissionPrincipal principal, String conversationId,
                                 String proposalId, String payloadHash);
    }

    interface TaskAuthorizationPort {
        ProofResult proveCommand(PermissionPrincipal principal, String conversationId,
                                 String taskId, TaskCommandType command);
    }

`ProofResult` 至少区分 `PROVED`、`DENIED`、`UNAVAILABLE`，并且不携带秘密。Permission 直接 Maven 依赖最终只保留 Common 和自动配置所需 Spring API；必须删除 Contracts 依赖以及 confirmation/proposal 共享类型，不留待实施者选择。Auth、File、Conversation、Task 与 App 依赖 Permission-owned 端口来提供 adapter；Permission 不 import 它们的内部实现。

## Revision Notes

2026-07-17 / Codex: 完成 Permission 基础设施子范围的 fault-isolation 收尾。新增 File/Conversation concrete proof adapter 的依赖异常测试，并用真实 `DefaultPermissionPolicy` 覆盖 Administrator、Conversation、Asset、Proposal、Task 五个 proof 失败位置，证明 Proposal 不被 claim、Task 不创建、durable replay 不创建第二个 Task。复验 File 33、Conversation 40 项测试和八个 infra 模块 + Permission 的真实依赖 reactor；记录完整触达 reactor 当前被无关 Eval trace 测试阻断，并把 canonical owner namespace/tool capability catalog 明确保留给总计划后续里程碑。

2026-07-17 / Codex: 执行 Milestone 0-3 并完成 Milestone 4 的组合根、required-proof 启动失败测试、负向扫描、触达模块与前端验收。记录服务端注入的 Proposal publication 二次授权、ephemeral Proposal/Task 幂等前置能力补齐、并发 confirm 完成信号，以及阻止全 reactor 通过的两条无关 Loop 失败。根据完成后的双轴 code review，进一步修复 task-bound replay、`CONFIRMING` proof、payload 重算、descriptor capability 和缺 runtime/Plan fact 的 fail-closed 行为；Milestone 4 因完整 fault-injection 矩阵尚未完成而继续保持未完成。

2026-07-17 / Codex: 创建本计划。依据提交 `0faa171` 相对 `b620027` 的 Permission 设计差异和当前源码审计，把任务定义为从 confirmation-token 三态闸门到 current-facts deny-first 授权的机制替换；明确 Permission-owned principal/scope/plan 视图、五类可信证明端口、两态 decision、direct Proposal confirm、跨模块原子迁移、故障注入、微提交与快速搜索关键词。计划同时记录当前工作树冲突边界，避免覆盖正在进行的 Cache 和 Lint 改动。

2026-07-17 / Codex: 根据用户“重构性、不为兼容保留旧代码”的要求收紧迁移策略。删除了临时旧参数适配和缺 Bean fallback 的选择，明确采用单个跨模块原子切换提交：新机制、全部消费者迁移与旧 token/contracts/Web 类型删除必须同时完成，任何实施提交结束时不得出现双轨生产代码。
