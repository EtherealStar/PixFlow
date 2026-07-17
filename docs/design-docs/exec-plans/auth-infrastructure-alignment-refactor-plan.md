# 将 Auth 重构为仅允许 Configured Administrator 的认证基础设施

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`，并作为 `docs/design-docs/exec-plans/backend-architecture-alignment-refactor-plan.md` 的 Auth 基础设施子计划执行。执行者只依赖当前工作树和本文，就应能把 `pixflow-infra-auth`、应用认证入口、STOMP 鉴权和前端登录运行时一次性切换到最新的单管理员模型。每个停止点都要更新本文；改变接口、错误语义、配置、阶段顺序、测试命令或兼容策略时，必须同步更新四个 living sections 和文末 `Revision Notes`。

## Purpose / Big Picture

完成后，公开部署的 PixFlow 只允许环境变量 `PIXFLOW_AUTH_ADMIN_USERNAME` 指定的 Configured Administrator 登录并继续使用现有 Access Session。数据库可以保留其他 Historical Account，但它们登录、刷新、调用 `/api/auth/me`、携带旧 JWT 访问 HTTP 接口或连接 STOMP 时都会被拒绝；拒绝响应不暴露账号存在性或“不是管理员”这一内部事实。修改配置、禁用账号或删除账号后，无需等待 JWT 自然过期，下一次请求就会失去资格。

公开注册能力会从后端和前端彻底消失。`POST /api/auth/register` 没有 controller mapping、service 方法、匿名白名单、DTO、前端请求函数、store/runtime 方法、表单、路由模式或兼容入口，因此请求得到 404。登录页只保留用户名和密码，并以不可交互文本显示“暂未开放注册”。短期 access JWT、HttpOnly refresh cookie、Redis 原子轮换、access blacklist、登录限流和现有账号状态检查继续工作。

开发者可以通过模块测试、MockMvc、真实 Redis 测试、前端测试和手工 HTTP/STOMP 场景观察结果：Configured Administrator 登录成功；Historical Account 即使密码正确也收到与坏密码同类的非枚举失败；管理员身份被重新配置后旧 refresh、旧 access JWT 和 STOMP CONNECT 都失败；注册路径返回 404；前端内存中不存在注册模式或注册请求。

## Progress

- [x] (2026-07-17) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md` 和 `docs/design-docs/exec-plans/` 下三份活动计划。
- [x] (2026-07-17) 阅读 `docs/design-docs/design.md` 的最新架构差异、`docs/design-docs/infra/auth.md`、`docs/design-docs/infra/permission.md`、`docs/design-docs/frontend/shell-routing-auth.md`、`CONTEXT-MAP.md`、`pixflow-infra-auth/CONTEXT.md` 和领域文档说明。
- [x] (2026-07-17) 对比提交 `0faa1713204785303c49807430dc614d2ee5af6d` 与父提交 `b6200272bdd2c17aad8ca80bfe9eb8d609b04b66`，确认 Auth 设计发生重写而生产代码没有同步变化。
- [x] (2026-07-17) 审计 Auth 生产源码、App controller/STOMP 接线、前端认证运行时、测试和 completed Auth 计划，区分应保留的安全机制与必须删除的旧注册/多账号访问机制。
- [x] (2026-07-17) 创建本中文重构 ExecPlan，固定一次性切换策略、实现机制、接口收窄、验证闭环和快速搜索关键词。
- [x] (2026-07-17) Milestone 0：保存脏工作树基线，固定 AuthService、HTTP、STOMP 与 Web runtime/UI 测试 seam，并记录缺失 admin 配置和 Historical Account 可登录的 red 证据。
- [x] (2026-07-17) Milestone 1：新增统一 `UsernameNormalizer`、必填 `pixflow.auth.admin-username`、`AdministratorEligibility` 与数据库实现；login、refresh、access JWT 逐边界读取当前账号与配置。
- [x] (2026-07-17) Milestone 2：删除后端 register controller/service/DTO/错误码，收窄 `AuthPrincipal` 与 `UserView`，并增加公开能力负向守护。
- [x] (2026-07-17) Milestone 3：logout 改为匿名幂等 cookie cleanup，HTTP 和 STOMP 统一复用 `authenticateAccessToken`；Auth-owned eligibility interface 已可供 Permission 后续接线，Permission policy 接线仍由其活动子计划负责。
- [x] (2026-07-17) Milestone 4：删除 Web register API/runtime/store/UI/router mode，只保留登录表单、内存 access token 与静态“暂未开放注册”。
- [ ] Milestone 5 部分完成（已完成：Auth 44 tests、App Auth 4 tests、Web 118 tests、Auth 范围 lint、typecheck/build、严格 Auth/App Checkstyle/SpotBugs、负向搜索；剩余：Permission/Conversation 工作树恢复可编译后运行全 reactor，及真实预置账号端到端场景。当前 Web 全量 lint 被 Auth 范围外的 `ProposalCard.vue` 与 `ChatPage.vue` 并行改动阻断）。

## Surprises & Discoveries

- Observation: 最新提交是目标设计提交，不是实现提交；Auth 文档从通用单机注册账号体系缩减为单 Configured Administrator 模型。
  Evidence: `git diff b620027 0faa171 --numstat -- docs/design-docs/infra/auth.md` 为 27 行新增、414 行删除；同一提交对 `pixflow-infra-auth`、App Auth controller 和 Web Auth 源码无改动。

- Observation: 当前后端仍完整支持公开注册，而目标要求该路由不存在并返回 404。
  Evidence: `AuthController.register`、`AuthService.register`、`RegisterRequest`、`AUTH_USERNAME_TAKEN`、`AUTH_USERNAME_INVALID`、`AUTH_PASSWORD_INVALID`、SecurityFilterChain 的 `/api/auth/register` permit rule 和 `JwtAuthenticationFilter` 的注册跳过规则仍存在。

- Observation: 当前所有 ACTIVE 数据库账号都能登录和继续使用会话，没有 Configured Administrator 配置或每边界资格检查。
  Evidence: `AuthProperties` 没有 `adminUsername`；`AuthService.login`、`refresh`、`authenticateAccessToken` 只检查密码、session、blacklist 和 `ACTIVE`；`AuthWebSocketInterceptor` 复用同一个未做管理员匹配的 access-token 入口。

- Observation: 现有安全硬化主体仍符合目标，不应因本次架构重构被重写。
  Evidence: JWT 已强制 HS256/header/claim/issuer 校验和强 secret；refresh 使用 `AuthSessionStore.consume`；Cookie 默认 Secure/HttpOnly/SameSite；logout 有 blacklist；登录失败使用 Redis 原子计数；账号状态和 token 撤销均 fail closed。

- Observation: `AuthPrincipal` 暴露 `status` 和 `authorities`，但仓库没有业务调用方读取 authorities，也没有角色授权规则；当前固定填入 `ROLE_USER` 与“唯一管理员资格不来自 JWT role”冲突。
  Evidence: 生产搜索只有 `JwtAuthenticationFilter` 把 `principal.authorities()` 映射到 Spring authorities，`UserView` 读取 status；没有 `hasRole`、`ROLE_ADMIN` 或其他 authorities 消费者。

- Observation: 目标文档把 logout cookie cleanup 列为匿名表面，而当前 `/api/auth/logout` 要经过 `.anyRequest().authenticated()`。
  Evidence: `AuthAutoConfiguration` 没有 logout permit rule，`JwtAuthenticationFilter.shouldNotFilter` 也不跳过 logout；controller 本身已能在 access token 无效时幂等清 cookie。

- Observation: 前端仍以注册为默认路径，旧能力分布在 API、runtime、Pinia store、LoginPage、router query、redirect helper 和多组测试中，不能只隐藏一个 tab。
  Evidence: `LoginPage.vue` 定义 `AuthMode = 'register' | 'login'` 和完整注册表单；router 默认追加 `mode=register`；`auth.ts`、`authSession.ts` 和 store 都公开 `register`。

- Observation: 当前工作树有大量来自 Lint 与小基础设施计划的用户修改，Auth 生产源码目前未修改；本计划实施时必须保护这些工作并与 Checkstyle 计划串行验证。
  Evidence: `git status --short` 显示 Common、File、Cache、MQ、Storage、Vector、Rubrics 和计划文档已有修改；活动 Lint 计划已清空 Auth 的 suppression，并明确 Maven reactor 不可并行运行。

- Observation: 现有 STOMP interceptor 在包装后的 accessor 上写入 principal 后仍返回原消息，导致后续管线读不到认证用户。
  Evidence: 新增 `AuthWebSocketInterceptorTest.connectUsesTheSameAccessTokenEligibilityBoundary` 首次运行时 `getUser()` 为 null；改为用更新后的 headers 重建消息后 2 个 STOMP 合同测试通过。

- Observation: 当前 Permission 活动重构已删除 confirmation token 类型，但 Conversation 尚未迁移，阻断 Auth 之后的全 reactor 组合验证。
  Evidence: `mvn -pl pixflow-app -am -Dmaven.test.skip=true package` 在 `pixflow-conversation` 编译失败，缺失 `com.pixflow.harness.permission.token.ConfirmationTokenService`；启用测试编译时更早在 Hooks 的旧 `PermissionSource.DEFAULT_ALLOW` 引用失败。这些文件不属于本 Auth 计划，未越界修改。

- Observation: 仅删除 register mapping 和匿名白名单会让 Spring Security 在 DispatcherServlet 判断无路由前返回 401，不能自然得到目标 404。
  Evidence: 真实 `SecurityFilterChain` 测试首次得到 `AUTH_TOKEN_MISSING` 401；认证入口改为查询 `RequestMappingHandlerMapping` 后，未知 register 路由返回 404，而真实受保护 controller 仍返回 401。

- Observation: 最终双轴审查首次发现开发默认管理员、终态 401/logout 前端清理和非法用户名限流仍有偏差；这些偏差均在交付前修复并重新验证。
  Evidence: `application-dev.yml` 改为无默认值环境绑定；Web 增加统一失效事件并整页返回 Login；非法 username 使用有界 throttle key 计数；复审 Standards 与 Spec 均无代码发现。

## Decision Log

- Decision: 采用开发期一次性切换，不保留 `register` 的 deprecated 方法、隐藏 controller、兼容转发、旧前端 mode、旧 DTO 或旧错误码。
  Rationale: 用户明确要求重构性变更且不要为兼容保留旧代码；双路径会让注册能力可被误接回匿名表面，也扩大 Auth interface。
  Date/Author: 2026-07-17 / Codex

- Decision: `pixflow.auth.admin-username` 是必填、非空、按现有 username 规则归一化的配置，生产由 `PIXFLOW_AUTH_ADMIN_USERNAME` 提供；开发 profile 也必须显式给值。
  Rationale: 资格源必须唯一、可审计且启动期可验证。Spring relaxed binding 会把 `PIXFLOW_AUTH_ADMIN_USERNAME` 映射到该属性；不提供默认管理员可避免部署遗漏时意外开放任意账号。
  Date/Author: 2026-07-17 / Codex

- Decision: 数据库存在零个、一个或多个 Historical Account 都不阻断启动；启动期不查询并强制“恰好一个账号”。
  Rationale: 设计明确允许历史账号保留，Configured Administrator 是配置选择，不是数据库行数推断。干净数据库的账号预置属于部署操作，不恢复公开注册。
  Date/Author: 2026-07-17 / Codex

- Decision: 管理员资格集中为 Auth 拥有的 `AdministratorEligibility` seam；生产 adapter 以当前数据库账号与当前配置重新验证，测试使用内存 adapter。login、refresh、JWT HTTP、`/me`、STOMP 和 Permission 都跨同一 seam，不在各 controller 重复字符串比较。
  Rationale: 资格包含配置匹配、账号存在和 ACTIVE 三个事实。集中后一次修复覆盖所有调用方，形成 locality；Permission 只学习一个窄 interface，不依赖 Auth persistence 或 JWT implementation。
  Date/Author: 2026-07-17 / Codex

- Decision: 各认证边界使用其已有的普通失败族隐藏管理员不匹配：login 返回 `AUTH_INVALID_CREDENTIALS`，refresh 返回 `AUTH_REFRESH_INVALID`，access JWT/`me`/STOMP 返回 `AUTH_TOKEN_INVALID`；日志只记录脱敏 reason tag，不把差异返回客户端。
  Rationale: 同一错误族能防止通过响应区分“账号不存在、密码错误、Historical Account、管理员配置已改变”。不同协议仍保留客户端已经依赖的登录、刷新和 bearer-token 恢复策略。
  Date/Author: 2026-07-17 / Codex

- Decision: 删除 `AuthPrincipal.authorities` 和 `status`，公开 principal 收窄为当前调用方实际需要的 `userId`、`username`、`displayName`；Spring Authentication 使用空 authorities，动作授权由 Permission 决定。
  Rationale: role/status 是无消费者的浅表面，并可能被误用为管理员资格。账号 ACTIVE 与 Configured Administrator 是创建 principal 前已验证的当前事实，不是让调用方自行解释的字段。
  Date/Author: 2026-07-17 / Codex

- Decision: logout 作为匿名、幂等的凭据清理端点；controller 尝试撤销 cookie session 和可解析 access JTI，无效或缺失凭据也清 cookie 并成功返回。
  Rationale: 这满足目标匿名表面并避免坏/过期 access token 阻止浏览器清除 refresh cookie。logout 不授予任何能力，因此 permitAll 不扩大业务访问。
  Date/Author: 2026-07-17 / Codex

- Decision: 保留 JWT 签名/claim 校验、Redis refresh 原子 consume、blacklist、限流、密码 BCrypt 和 user_account ACTIVE/DISABLED schema；本计划只把管理员资格合并进这些既有入口。
  Rationale: 这些机制已经通过安全加固并直接支撑目标 Access Session。删除它们会把范围扩大为无关认证重写。
  Date/Author: 2026-07-17 / Codex

- Decision: `AdministratorEligibility` 最终只公开 `requireEligible(long userId)` 并返回最小 `AuthPrincipal`；边界专属错误由 `AuthService` 映射。
  Rationale: 单方法足以让 Auth 与 Permission 读取当前事实，同时不会泄漏 mapper/entity/config 或让调用方自行解释布尔管理员标志。login、refresh、token 仍分别返回 credentials、refresh、token 错误族。
  Date/Author: 2026-07-17 / Codex

- Decision: Security 认证入口只用 MVC controller mapping 区分“未知路由”和“真实受保护端点”；mapping 探测异常时仍按认证失败关闭。
  Rationale: 静态资源的 `/**` fallback 不能证明 API 路由存在。直接查询 `RequestMappingHandlerMapping` 才能让删除后的 register 得到 404，同时不把它重新加入匿名白名单。
  Date/Author: 2026-07-17 / Codex

- Decision: Web transport 对非过期终态 401 统一清空认证，并由应用组合根整页导航 `/login`；显式 logout 复用同一事件，只有 `AUTH_TOKEN_EXPIRED` 可以 refresh/replay 一次。
  Rationale: transport 不能依赖 Pinia/router，否则形成循环依赖；失效事件保持边界单向，整页导航还能释放 composable 内存中的请求、附件和 turn 队列。
  Date/Author: 2026-07-17 / Codex

## Outcomes & Retrospective

Auth 子计划的生产切换已经完成。最终 `AuthService` 只公开 login、refresh、logout、authenticateAccessToken；`AuthPrincipal` 只有 userId、username、displayName。后端删除 RegisterRequest、register mapping/service、三项注册错误码和 ROLE_USER 表面；Web 删除 register API/runtime/store/form/mode。配置使用 `pixflow.auth.admin-username`，开发 profile 无默认值绑定 `PIXFLOW_AUTH_ADMIN_USERNAME`。Historical Account login 映射 `AUTH_INVALID_CREDENTIALS`，资格变化后的 refresh/access 分别映射 `AUTH_REFRESH_INVALID`/`AUTH_TOKEN_INVALID`；终态 access 401 与 logout 都清理 Web 内存认证并返回 Login。Auth 44 tests、App Auth 4 tests、Web 118 tests、Auth 范围 lint、typecheck、build、Auth/App Checkstyle 与 SpotBugs 均通过，生产负向搜索为零命中。当前 Web 全量 lint 被 Auth 范围外的两处并行改动阻断；全 reactor 与真实账号端到端仍待活动 Permission/Conversation 原子迁移恢复后执行，因此本计划尚未整体归档。

## Context and Orientation

仓库根目录为 `D:\study\PixFlow`。`pixflow-infra-auth` 是认证基础设施模块，拥有数据库账号读取、密码验证、JWT、refresh session、blacklist、限流、SecurityContext 和当前 principal。`pixflow-app/src/main/java/com/pixflow/app/auth` 暴露 REST 和 STOMP adapter。`pixflow-web` 持有内存 access token、HttpOnly refresh cookie 驱动的启动恢复和登录页面。`pixflow-permission` 负责动作授权，不拥有登录、JWT 或管理员配置。

本计划沿用 `pixflow-infra-auth/CONTEXT.md` 的词汇。Configured Administrator 是部署配置指定的唯一可访问账号，不能称为“第一个用户”或 `role=ADMIN`。Administrator Eligibility 是账号仍匹配当前配置且仍可用的当前事实。Access Session 是短期 access credential 与轮换 refresh credential 共同表示的可撤销认证关系。Historical Account 是仍在数据库但不再具有应用访问资格的账号；保留其数据库行不是兼容旧注册接口。

当前主流程为：login 查任意 username、验密码和 ACTIVE 后签发 token；refresh 原子消费 session、按 userId 查 ACTIVE 后轮换；JWT filter 解析 token、查 blacklist 和 ACTIVE 后创建含 `ROLE_USER` 的 `AuthPrincipal`；App controller 用 principal 返回 `/me`；STOMP interceptor 直接复用 `AuthService.authenticateAccessToken`。缺口是这些入口均没有 Configured Administrator 资格校验，且 register 从 Web 到数据库仍是可达生产路径。

目标模块要成为更深的模块：调用方只需学习 login、refresh、logout、authenticate 和 Administrator Eligibility 这组小 interface；username 归一化、配置匹配、账号状态、错误映射、session/JWT 细节留在 implementation 内。测试与调用方都跨同一 interface，不读取 mapper、session key 或 role flag。

## Mechanism and Approach

配置绑定阶段把 `pixflow.auth.admin-username` 归一化为与账号写入时相同的小写 username，并按 `^[a-z0-9_]{3,32}$` 验证。为了避免 AuthProperties 和 AuthService 各自复制规则，把 username 规范化提取为 Auth 内部的单一 value/normalizer，例如 `AccountUsername`；配置和登录输入都使用它。配置缺失或非法时 Spring context 启动失败，数据库暂时没有对应行时 context 仍启动，login 只会得到非枚举失败。

Administrator Eligibility 每次读取当前事实而不信任 JWT role、前端 user 或旧 refresh session 中的 username。login 先执行限流和密码验证，再要求账号 username 等于当前配置且 ACTIVE；即使 Historical Account 密码正确，响应也与坏密码相同。refresh 在原子 consume 后按 userId 读当前账号，再检查配置匹配和 ACTIVE，失败时不签发新 token。JWT 请求和 STOMP 每次按 claim userId 回源数据库，检查 token claim username 与当前账号一致，再检查资格。`/me` 已经经过 JWT filter，因此使用该次请求刚建立的 principal；Permission 在副作用边界可再次调用 Auth-owned eligibility interface，依赖不可用时 fail closed。

注册采用删除而不是 feature flag。删除 controller 方法、service 方法、request DTO、注册校验辅助、注册专用错误码和测试；SecurityFilterChain 与 JWT filter 不再认识 register。前端删除 API/runtime/store 方法、注册 mode/form/validation/toast、`mode=register` query 和对应 fixtures。404 来自 Spring 没有 mapping，而不是返回 403/410 的占位 controller。

Access Session 保留现有两凭据模型。access JWT 只保留 `sub, uname, iat, exp, jti, typ`，没有 role claim；refresh cookie 仍是 Secure、HttpOnly、SameSite，服务端只存 hash并原子 consume。旧 token 中即使 username 曾是管理员，每次请求仍以数据库和当前配置重新判定，因此改配置或禁用账号立即生效。logout 是唯一允许在坏凭据下继续完成的清理操作。

## Plan of Work

### Milestone 0：冻结基线和先失败的目标契约

先记录 `git status --short`，确认 Auth、App Auth 和 Web Auth 文件是否出现新的用户修改。不要改动活动 Lint 计划正在处理的其他模块，也不要并行运行 Maven reactor。读取当时所有活动计划；若总计划 Milestone 2 或前端计划已开始修改相同文件，先在两个计划 Progress 记录单一文件所有者。

在 `pixflow-infra-auth` 增加目标行为测试，并让它们先在旧实现上失败：配置缺失/非法 admin username 启动失败；Historical Account 密码正确仍不能登录；配置切换后 refresh 和 access token 失效；禁用 Configured Administrator 后所有边界失败；Historical Account 的存在不导致 context 启动失败。为 filter chain 增加 `/api/auth/register` 无 mapping/非白名单探针和 anonymous logout 测试。为 App 增加 MockMvc 合同测试，为 STOMP 增加管理员失配测试。前端先把 LoginPage/route/API 的目标测试改为只允许登录并确认旧注册符号仍使测试失败。

同时建立负向架构守护：Auth 生产代码不得出现 `register`、`RegisterRequest`、注册专用错误码、`ROLE_USER`、`ROLE_ADMIN` 或从 JWT role 判断管理员；Web 生产代码不得出现 `/api/auth/register`、注册表单或 `mode=register`。守护要检查生产源码和 Spring mappings，不能只检查 UI 文案。

### Milestone 1：集中 Configured Administrator 与 Administrator Eligibility

在 `AuthProperties` 新增必填 `adminUsername`，并在 `pixflow-app/src/main/resources/application-dev.yml` 显式绑定 `${PIXFLOW_AUTH_ADMIN_USERNAME}`；开发和生产配置都不提供代码默认值。提取 username value/normalizer，供配置、login 和测试共同使用，删除散落的静态 normalize 规则。

在 `pixflow-infra-auth` 定义窄 `AdministratorEligibility` interface 和数据库实现。它根据 userId 读取最新 `user_account`，验证行存在、username 与配置一致、状态 ACTIVE，并返回收窄后的 `AuthPrincipal`；对外不暴露 `UserAccountEntity`、mapper、配置对象或布尔 `isAdmin`。AuthService 的 login、refresh、authenticateAccessToken 都调用同一 implementation；token claim username 与数据库 username 不一致也失败。内部为不同入口把资格失败映射到 login/refresh/token 的非枚举错误族。

加入配置切换测试：同一 JVM 测试可用不同 AuthProperties/上下文重建模拟部署变更，不需要让配置对象在运行时热刷新。证明旧 access/refresh 在新配置实例下失败，新管理员账号可以登录，旧账号行仍保留。

### Milestone 2：删除注册并收窄 Auth interface

删除 `AuthController.register`、`AuthService.register`、`RegisterRequest.java`、注册相关测试、`validatePassword`、`cleanDisplayName` 和仅被注册使用的 duplicate-key 处理。审计 `AuthErrorCode`，删除只服务注册的 `AUTH_USERNAME_TAKEN`、`AUTH_USERNAME_INVALID`、`AUTH_PASSWORD_INVALID`；保留 login、refresh、token、disabled、throttle 和 access-denied 错误。数据库 `user_account` 表、`PasswordHasher` 和 mapper 保留，因为部署外预置账号仍需登录。

把 `AuthPrincipal` 改为 `userId, username, displayName`，删除 status 和 authorities；更新 `JwtAuthenticationFilter` 使用空 authorities，更新 `UserView` 和前后端 AuthUser 类型只保留真实展示字段。删除所有 `ROLE_USER` 和 role-based administrator 假象。同步更新 Conversation controller tests 中手工 principal 构造，但不重构 Conversation 业务。

删除 SecurityFilterChain 对 register 的 permit rule和 `JwtAuthenticationFilter.shouldNotFilter` 的 register 分支。用 MockMvc 证明 POST register 返回 404，且不会命中 AuthService、mapper 或生成 cookie。不能增加 `@Deprecated` wrapper、feature flag 或 410 compatibility controller。

### Milestone 3：对齐 HTTP、logout、STOMP 和 Permission seam

精确匿名表面为 login、refresh、logout cookie cleanup、必要的 health/static/OPTIONS/ws handshake。SecurityFilterChain permit POST logout，JWT filter 对 logout 不强制认证；AuthController 仍从 cookie 和 header 提取凭据并调用幂等 logout。测试无 token、坏 token、过期 token和有效 token 四种 logout 都清 cookie；只有可解析且未过期的 access JTI 被 blacklist，refresh session 存在时被删除。

HTTP JWT filter、`/me` 和 STOMP CONNECT 统一从 Auth 的 authenticate interface 获得已通过当前 Administrator Eligibility 的 principal。STOMP 不自行比较配置，也不信任 CONNECT header 中的 username/role。增加测试证明 Historical Account 的合法 JWT、配置切换前签发的 JWT、disabled admin JWT 都无法建立 STOMP user。

向 `pixflow-permission` 交接 `AdministratorEligibility` interface，但本计划不重写 Permission 的 Proposal/Asset/Task 规则。如果总计划 Milestone 2 同批执行，则在 Permission evaluation 第 2 步接入并增加 fail-closed 测试；如果尚未执行，在本计划 Progress 明确记录“Auth interface 已就绪，Permission adapter 待总计划接线”，不得复制 eligibility 逻辑或让 Permission 读取 Auth mapper。

### Milestone 4：一次性删除前端注册能力

删除 `pixflow-web/src/api/auth.ts` 的 `RegisterRequest` 与 `register`，删除 `runtime/authSession.ts`、`stores/auth.ts` 的 register interface/implementation，删除对应 store/API 测试案例和 mock。`LoginPage.vue` 删除 AuthMode、注册 form、注册校验、tab、注册提交和 query 同步，只保留 username/password 登录表单与静态“暂未开放注册”文本。

router、`useAuthRedirect.ts` 和测试不再生成或解析 `mode=register`/`mode=login`；未认证访问统一跳转 `/login?redirect=...`。启动 bootstrap 仍保持一次 single-flight refresh 后加载 `/me`，access token 仍只存内存；只允许 `AUTH_TOKEN_EXPIRED` 触发一次 refresh 和 replay。管理员失配返回 token-invalid/refresh-invalid 时清内存并回 Login，不进入 refresh loop。

运行前端四项验证并用源码负向搜索证明没有 register 请求、注册 mode 或表单。UI 保留的“暂未开放注册”文案是产品说明，不算 register 机制命中。

### Milestone 5：真实依赖、全仓验证与交接

运行 Auth/Common/Cache 测试，并在 Redis 可用时执行 refresh consume、blacklist 和限流真实依赖测试。运行 App MockMvc/STOMP 定向测试、前端四项验证和严格 Maven verify。所有 Maven reactor 串行运行；若 Docker 不可用，记录具体测试和 skipped 数，不能把纯 mock 结果写成 Redis 已验证。

手工或自动端到端场景先预置两个 ACTIVE 账号：配置指向其中一个。验证管理员登录 200、Historical Account 相同正确密码仍得到普通 401、register 404、`/me` 200、STOMP CONNECT 成功。随后用新应用配置指向另一个账号，验证旧 access/refresh/STOMP 失败、新管理员登录成功、数据库两个账号行都还存在。最后禁用新管理员，验证其所有入口失败。

更新本计划 living sections、后端总计划 Milestone 2 交接记录、`pixflow-infra-auth/CONTEXT.md`（仅当实现术语发生必要细化）和相关 API 摘要。completed Auth 计划只保留历史事实，不把旧注册目标改写成当前目标。

## Tiny Commit Sequence

建议提交顺序为 A1 增加先失败的管理员资格与无注册契约测试，A2 增加配置和 eligibility implementation，A3 把 login/refresh/JWT/STOMP 接入统一资格检查，A4 删除后端 register 和注册专用错误/测试，A5 收窄 principal/UserView 并更新直接消费者，A6 对齐 anonymous logout 与 filter chain，W1 删除前端注册 API/runtime/store，W2 把 LoginPage/router 改为仅登录，V1 完成架构守护、文档和组合验证。每个提交结束时必须可编译；A4 不得只隐藏 controller 而把 service 保留下来，W1/W2 合并前不得留下调用已删除 API 的不可运行状态。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 执行。开始和每个停止点运行：

    git status --short
    git diff --check
    rg --files docs/design-docs/exec-plans | rg -v "completed[\\/]"

复现本计划的提交对比：

    git show -s --format=fuller 0faa171
    git diff b620027 0faa171 --numstat -- docs/design-docs/infra/auth.md
    git diff b620027 0faa171 --name-status -- pixflow-infra-auth pixflow-app/src/main/java/com/pixflow/app/auth pixflow-web/src

后端旧路径基线与完成后的负向检查：

    rg -n "register|RegisterRequest|AUTH_USERNAME_TAKEN|AUTH_USERNAME_INVALID|AUTH_PASSWORD_INVALID|ROLE_USER|ROLE_ADMIN" pixflow-infra-auth/src pixflow-app/src/main/java/com/pixflow/app/auth
    rg -n "admin-username|PIXFLOW_AUTH_ADMIN_USERNAME|AdministratorEligibility|authenticateAccessToken" pixflow-infra-auth pixflow-app pixflow-permission

完成后第一条在生产源码预期无输出；允许 completed 历史文档保留旧名称。第二条应命中配置、集中资格 interface、Auth 入口和测试，不能只命中 UI 或文档。

前端旧路径基线与完成后的负向检查：

    rg -n "/api/auth/register|RegisterRequest|register\(|mode=register|AuthMode|registerForm|注册优先" pixflow-web/src

完成后预期无输出。“暂未开放注册”应单独存在于 LoginPage 的静态文本中：

    rg -n "暂未开放注册" pixflow-web/src/pages/LoginPage.vue

模块验证：

    mvn -pl pixflow-infra-auth -am test
    mvn -pl pixflow-infra-cache,pixflow-infra-auth -am -DskipTests verify
    mvn -pl pixflow-app -am -DskipTests test
    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build

最终串行运行：

    mvn -DskipTests verify
    mvn verify
    git diff --check
    git status --short

若 `mvn verify` 因 Docker/Testcontainers 失败，记录 `docker info`、首个失败测试和栈帧，同时分别证明 Auth 定向测试、前端四项验证和 `mvn -DskipTests verify` 的真实结果。不得删除集成测试或增加 skip 来制造通过。

## Validation and Acceptance

配置验收要求未设置或设置非法 `PIXFLOW_AUTH_ADMIN_USERNAME` 时 context 启动失败，并明确指出 `pixflow.auth.admin-username`；合法配置但数据库没有该账号时 context 启动成功，login 返回普通认证失败。大小写和前后空白按唯一 username normalizer 处理，不能让配置和数据库使用两套比较规则。

登录验收要求只有 Configured Administrator 且状态 ACTIVE、密码正确时返回 access token 和 refresh cookie。不存在账号、坏密码、Historical Account 正确密码和管理员配置失配对客户端使用同一 `AUTH_INVALID_CREDENTIALS` 形状；限流仍在昂贵 BCrypt 前检查，失败仍计入 username/IP 防护，响应不泄露目标管理员名。

持续资格验收要求 refresh、每个 JWT HTTP 请求、`/me` 和 STOMP 都回源当前账号并检查当前配置。更改配置、禁用或删除账号后，旧 token 不能继续访问；JWT 中伪造 role、frontend user flag 或 CONNECT username 都不能恢复资格。JWT claims 中不存在 role。

注册删除验收要求 POST `/api/auth/register` 为 404，不产生 Set-Cookie、不调用 mapper、不创建账号；生产 Java/TS/Vue 不存在 register symbol、路由、DTO、匿名规则或兼容 adapter。数据库仍可有 Historical Account，且它们不能访问应用。

凭据验收要求 access token 仅保存在 Web runtime memory；refresh token 只存在 Secure/HttpOnly/SameSite cookie 和服务端 hash。并发 refresh 仍只有一次成功；logout 在缺失/坏/过期凭据下也清 cookie，有效 access JTI 被 blacklist。客户端只有 token expired 才自动刷新一次，管理员失配不会形成 refresh loop。

接口深度验收要求业务 controller 只消费收窄的 `AuthPrincipal`，不学习 JWT claims、账号状态、role、管理员配置或 mapper。Permission 只消费 Auth-owned eligibility interface，不读取 Auth implementation。测试通过公开 interface 断言行为，不依赖 Redis key 或私有字符串比较。

## Idempotence and Recovery

搜索、编译和测试命令可以重复运行。管理员配置切换测试创建独立 Spring context 或 fixture，不修改真实环境变量和用户数据库。手工端到端使用预置测试账号，不通过恢复 register 路由准备数据。

不得执行 `git reset --hard`、`git checkout --`、整仓 restore 或删除用户工作。实施前检查工作树；一个 Auth Java 文件的源码与其全部 Checkstyle suppression 是原子恢复单位。Auth suppression 当前已清空，不得为本重构新增 suppression。Maven reactor 必须串行运行，避免共享 `target/spotbugsTemp.xml` 冲突。

如果资格接线中途停止，至少保持 AuthService 的 login、refresh 和 authenticateAccessToken 同时使用新 eligibility，不能提交只保护 login 而让旧 JWT/refresh 继续生效的半迁移。若前端删除 register API 后尚未完成 LoginPage，必须在同一提交完成调用方删除或拆成先改 UI、后删死接口的两个均可编译提交；最终不保留死接口。

数据库 migration 不删除 Historical Account，不新增 role/admin 列，也不把配置写进数据库。干净环境需要由部署者在应用外预置一个 BCrypt password 的 ACTIVE 账号并使 username 与配置一致；这是一项部署前置条件，不应通过生产 bootstrap 默认密码或公开注册补偿。

## Quick Reference Search Keywords

查看最新提交为什么触发本计划，搜索 `git diff b620027 0faa171 -- docs/design-docs/infra/auth.md`、`Administrator-only Authentication`、`PIXFLOW_AUTH_ADMIN_USERNAME`、`Historical Account`、`every JWT-authenticated request`、`Registration is completely disabled`。

定位仓库领域语言与边界，搜索 `Configured Administrator`、`Administrator Eligibility`、`Access Session`、`Historical Account`、`Administrator Authentication -> all authenticated contexts`、`Deny-first Authorization Boundary`。

定位后端旧实现，搜索 `AuthController.register`、`AuthService.register`、`RegisterRequest`、`requestMatchers(HttpMethod.POST, "/api/auth/register")`、`isAnonymousAuthEndpoint`、`ROLE_USER`。定位应保留的安全机制，搜索 `AuthSessionStore.consume`、`CacheStore.consume`、`remainingTtlSeconds`、`AccessTokenBlacklist`、`recordFailureAndAssert`、`isSecretStrong`、`cookieSecure`。

定位管理员资格的所有必经入口，搜索 `AuthService.login`、`AuthService.refresh`、`authenticateAccessToken`、`JwtAuthenticationFilter`、`AuthController.me`、`AuthWebSocketInterceptor`、`PermissionPolicy.evaluate`。目标实现完成后再搜索 `AdministratorEligibility`，应能从这些入口追到同一 seam，而不是多处字符串比较。

定位前端旧注册路径，搜索 `/api/auth/register`、`RegisterRequest`、`auth.register`、`session.register`、`AuthMode`、`registerForm`、`mode=register`、`注册优先`。定位目标登录运行时，搜索 `bootstrap`、`refreshOnce`、`AUTH_TOKEN_EXPIRED`、`setAccessToken`、`clearAuthState`、`暂未开放注册`。

历史实现只作参考：`docs/design-docs/exec-plans/completed/auth-module-implementation-plan.md` 可定位模块骨架；`completed/review-auth-security-hardening-plan.md` 可定位 JWT、atomic consume、限流和测试模式。两者的 register 目标已经失效，不得复制或恢复。

建议直接使用：

    rg -n "register|RegisterRequest|AUTH_USERNAME_TAKEN|ROLE_USER" pixflow-infra-auth pixflow-app/src/main/java/com/pixflow/app/auth pixflow-web/src
    rg -n "admin-username|AdministratorEligibility|Configured Administrator|Historical Account"
    rg -n "login\(|refresh\(|authenticateAccessToken|AuthWebSocketInterceptor|PermissionPolicy"
    rg -n "AuthSessionStore.consume|AccessTokenBlacklist|recordFailureAndAssert|cookieSecure"

## Artifacts and Notes

提交对比基线：

    HEAD  0faa171 docs: align architecture, ADRs, and execution plans
    BASE  b620027 feat(app): expose task assets and progress events
    docs/design-docs/infra/auth.md  27 insertions, 414 deletions
    Auth/App/Web production changes in HEAD: none

当前机制与目标差距：

    register       controller + service + DTO + errors + Web UI/API remain reachable
    admin config   absent
    login          any ACTIVE account can authenticate
    refresh        any ACTIVE session account can rotate
    JWT / me       account ACTIVE is checked; configured username is not
    STOMP          reuses the same incomplete access-token check
    principal      exposes unused status + authorities and fixed ROLE_USER
    logout         currently authenticated, target allows anonymous cookie cleanup

目标请求链：

    login credentials
      -> throttle
      -> password verification
      -> AdministratorEligibility(current DB + current config)
      -> issue access JWT + rotating refresh session

    access JWT / STOMP
      -> strict JWT parse + blacklist
      -> current DB account
      -> AdministratorEligibility
      -> minimal AuthPrincipal

    refresh cookie
      -> atomic session consume
      -> current DB account
      -> AdministratorEligibility
      -> rotate credentials

## Interfaces and Dependencies

`AuthProperties` 最终包含语义等价的必填配置：

    @NotBlank
    private String adminUsername;

它由 `pixflow.auth.admin-username` / `PIXFLOW_AUTH_ADMIN_USERNAME` 绑定。配置校验使用与登录相同的 username value/normalizer，不允许空白、非法字符或另一个大小写策略。

Auth 对其他模块公开的 principal 最终收窄为：

    public record AuthPrincipal(
        Long userId,
        String username,
        String displayName
    ) {}

Auth-owned eligibility interface 应表达当前事实而不是缓存布尔值，形状可等价于：

    public interface AdministratorEligibility {
        AuthPrincipal requireEligible(long userId);
        AuthPrincipal requireEligible(AuthPrincipal principal);
    }

若 implementation 只需要一个方法，应删除另一个而不是保留便利重载。生产 adapter 查询当前 `user_account` 并验证 configured username 与 ACTIVE；测试 adapter 可在内存提供当前事实。不要暴露 `UserAccountEntity`、`AuthProperties` 或 `boolean isAdmin(String claimedUsername)`。

`AuthService` 最终公开行为应收敛为：

    AuthTokenResponse login(LoginRequest request, String ipAddress);
    AuthTokenResponse refresh(String refreshTokenValue);
    void logout(String refreshTokenValue, String accessTokenValue);
    AuthPrincipal authenticateAccessToken(String accessTokenValue);
    UserView me(AuthPrincipal principal);

不存在 `register`。如果 `me` 只是 `UserView.from(principal)` 的浅 pass-through，删除 `AuthService.me` 并让 App adapter 直接投影，进一步缩小 interface；以删除测试验证删除模块后复杂度不会泄漏到多个调用方。

Auth 的内部依赖保持 `pixflow-common`、`pixflow-infra-cache`、Spring Security、MyBatis/MySQL 和 BCrypt。`pixflow-permission` 只依赖 Auth-facing principal/eligibility interface；Auth 不依赖 Permission。`pixflow-app` 是 REST/STOMP adapter 和 cookie writer，不能拥有管理员比较。`pixflow-web` 不读取管理员配置，也不持有 role/admin flag。

## Revision Notes

2026-07-17 / Codex: 创建本计划。依据 `0faa171` 相对 `b620027` 的 Auth 设计重写，确认生产实现仍是公开注册、任意 ACTIVE 账号可登录的旧模型。计划按用户要求采用无兼容层的一次性重构：集中 Configured Administrator / Administrator Eligibility，覆盖 login、refresh、JWT、`/me`、STOMP 和 Permission seam；删除后端及前端注册全链路；收窄 principal 并删除 role/status 表面；保留已经正确的 JWT、atomic refresh、blacklist、限流和 Cookie 安全机制；提供负向搜索、真实依赖和端到端验收关键词与命令。

2026-07-17 / Codex: 完成 Auth 生产与 Web 一次性切换。新增 admin username 配置、统一 normalizer 和数据库 eligibility；删除注册全链路、注册错误码、principal role/status；对齐匿名 logout；修复 STOMP principal 未随消息传播的既有缺陷；记录 42 个 Auth、4 个 App Auth、116 个 Web 测试与前端四项验证证据。全 reactor 仍被活动 Permission/Conversation 半迁移阻断，未将其误记为 Auth 失败或完成。

2026-07-17 / Codex: 根据最终双轴审查补齐五项偏差：使用 MVC controller mapping 保证删除后的 register 经真实安全链返回 404；移除开发管理员默认值；让非法 username 仍进入有界限流计数并增加直接回归测试；终态 access 401 和 logout 统一清会话并整页返回 Login。复验 Auth 44 tests、App Auth 4 tests、Web 118 tests、Auth 范围 lint、typecheck/build、Auth/App strict verify 均通过；Standards 与 Spec 复审无代码发现。Web 全量 lint 和 Maven 全 reactor 的外部阻塞已分别记录。
