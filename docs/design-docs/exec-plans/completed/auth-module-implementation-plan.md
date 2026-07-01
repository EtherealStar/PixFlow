# 完整实现 `pixflow-infra-auth`：单机账号体系、JWT access token、Redis refresh session、黑名单与当前用户上下文

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`、`AGENTS.md`、`docs/design-docs/index.md`、`docs/design-docs/infra/auth.md`、`docs/design-docs/api.md`、`docs/design-docs/design.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。

执行完成后，PixFlow 将具备可落地的单机账号体系：用户可以注册、登录、刷新 access token、登出，并在后端稳定拿到当前用户身份；前端能够通过 `/api/auth/me` 恢复登录态；WebSocket/STOMP 与 HTTP 请求都能统一走同一套 access token；登录失败、账号禁用、token 失效、refresh 失效都能 fail-closed，而不是继续放行。

## Purpose / Big Picture

这项工作把“有界面但没有身份”的项目，补成“有完整登录态和资源归属”的单机系统。完成后，用户可以在前端注册并登录，刷新页面后依然保持会话，注销后立即失效；后端业务模块可以通过统一的 `AuthPrincipal` 识别当前用户，并据此给 conversation、file、task、memory 等顶层资源加上归属过滤。

从实现上看，这不是把认证逻辑散落到 controller 里，而是把身份能力收敛成一组可复用原语：JWT access token 负责短期访问态，Redis 负责 refresh 会话、黑名单和失败计数，Spring Security 过滤链负责把身份放进 `SecurityContext`，`AuthContextHolder` 负责给 service 层提供简单稳定的当前用户访问方式。

## Progress

本计划拆为 1 个基础设施实现阶段 + 1 个应用接线阶段 + 1 个联调验收阶段。每个停止点都必须在这里记录。

- [x] (2026-07-01 16:36+08:00) 新增 `pixflow-infra-auth` Maven 模块，并把它接入父 `pom.xml` 与模块依赖图。
- [x] (2026-07-01 16:36+08:00) 落地认证核心：用户模型、密码哈希、JWT access token、Redis refresh session、黑名单与失败计数。
- [x] (2026-07-01 16:36+08:00) 落地 Spring Security 接入：过滤链、当前用户上下文、`@CurrentUser` 解析、匿名放行规则。
- [x] (2026-07-01 16:36+08:00) 在 `pixflow-app` 暴露 `/api/auth/register`、`/login`、`/refresh`、`/logout`、`/me` 与 STOMP 鉴权接入。
- [ ] (2026-07-01 16:36+08:00) 补齐测试：注册/登录/刷新/登出/黑名单/禁用账号已由 `pixflow-infra-auth` 单测覆盖；失败限流、上下文解析、WS 鉴权仍需补专项测试。
- [ ] (2026-07-01 00:00+08:00) 完成联调验收：前端登录恢复、token 轮换、登出失效、owner_user_id 过滤链路打通。

## Surprises & Discoveries

- 2026-07-01：`web-frontend-implementation-plan.md` 的早期实现假设写过 “V1 单机无用户系统”，但本计划是更新后的 auth 专项计划，后端先补齐登录态；前端 token 注入和 `/api/auth/me` 恢复登录态仍需后续同步。
- 2026-07-01：`mvn -pl pixflow-app -am test` 在 3 分钟工具超时内未完成；改用 `mvn -pl pixflow-app -am -DskipTests package` 验证 app 编译与打包接线。

## Decision Log

- Decision: `auth` 作为独立基础设施模块实现，而不是塞进 `pixflow-app` 控制器层。
  Rationale: 认证是横切能力，后续多个业务模块都要依赖它；独立模块能保持边界清晰，也更利于测试和复用。
  Date/Author: 2026-07-01 / Codex

- Decision: access token 采用 JWT，refresh token 采用 Redis 服务端会话，不把 refresh token 设计成 JWT。
  Rationale: access token 需要短期自校验，refresh 需要可撤销、可轮换；Redis 会话更适合失效控制和登出。
  Date/Author: 2026-07-01 / Codex

- Decision: 认证失败一律 fail-closed，token 解析失败、refresh 缺失、账号禁用、风控命中都直接拒绝。
  Rationale: 认证系统的默认态必须是“不通过”，否则安全边界会被静默放宽。
  Date/Author: 2026-07-01 / Codex

- Decision: 当前用户信息通过 `SecurityContextHolder` 和 `AuthContextHolder` 双通道提供。
  Rationale: 前者兼容 Spring Security 生态，后者让业务 service 层调用更轻。
  Date/Author: 2026-07-01 / Codex

- Decision: 顶层业务对象必须补 `owner_user_id` 归属过滤，但这次实现只做认证底座与 app 接线，不在本计划里重构所有业务表。
  Rationale: 归属字段是跨模块改造，应该跟各业务模块的写入/查询计划一起落地，避免把 auth 计划拖成全仓重构。
  Date/Author: 2026-07-01 / Codex

- Decision: 本轮 JWT 采用 JDK `HmacSHA256` + Jackson 手写 HS256 实现，不引入额外 JWT 库。
  Rationale: 设计只要求单机 HS256 access token；手写小型实现能减少依赖面，并由单测覆盖签发、解析、过期和篡改拒绝。
  Date/Author: 2026-07-01 / Codex

## Outcomes & Retrospective

待完成。完成后应总结：哪些接口对前端可见、哪些身份语义已固化、哪些模块已经开始消费 `AuthPrincipal`，以及仍然未做的 SSO/MFA/多租户边界。

## Context and Orientation

当前仓库已有 `pixflow-app`、`pixflow-permission`、`pixflow-infra-cache`、`pixflow-conversation` 等模块，但没有 `pixflow-infra-auth`。`docs/design-docs/infra/auth.md` 已明确认证边界、token 模型、Redis 键、过滤链、错误码和测试策略，因此本计划只需要把文档内容转成可实现的工程步骤。

这里先定义几个后续会反复出现的术语：

- `access token`：短生命周期 JWT，客户端每次 HTTP 请求和 STOMP 连接都带它。
- `refresh token`：只存在于 HttpOnly Cookie 的随机串，服务端只存 hash，并用 Redis 会话记录它的有效性。
- `黑名单`：登出、改密、强制失效时，把当前 access token 的 `jti` 放进 Redis，直到该 token 自然过期。
- `AuthPrincipal`：后端对当前登录用户的统一表示，至少包含 userId、username、status、authorities/roles 的最小集合。
- `AuthContextHolder`：一个线程内可读取的当前用户持有器，供 service 层在没有 controller 参数的情况下访问用户身份。

本计划的实现重点是“统一入口、统一上下文、统一失效策略”。也就是说，认证信息不允许分散在各个 controller 自己解析；请求先经过过滤链，再进入业务层，业务层只读标准化后的 principal。

## 设计文档快速定位关键词

执行本计划时，先用下面关键词在参考文档里定位对应设计文本。

### `docs/design-docs/infra/auth.md`

- 模块定位与原则 → 搜 `文档定位与设计原则` 或 `单机优先`
- 与 `permission` 的边界 → 搜 `与 permission 的边界`
- 模块结构与依赖位置 → 搜 `模块结构与依赖位置`
- 账号模型与状态机 → 搜 `账号模型` 或 `用户状态机`
- Token 设计 → 搜 `Token 模型`、`access token`、`refresh token`
- 注册 / 登录 / 刷新 / 登出流程 → 搜 `注册 / 登录 / 刷新 / 登出流程`
- Redis 键与 TTL → 搜 `Redis 键与 TTL`
- 鉴权过滤链与上下文 → 搜 `鉴权过滤链与上下文`
- 前端与 WebSocket 接入 → 搜 `前端与 WebSocket 接入`
- 错误处理、限流与安全 → 搜 `错误处理、限流与安全`
- 配置项 → 搜 `配置项`
- 对其他模块的契约 → 搜 `对其他模块的契约`
- 测试策略 → 搜 `测试策略`

### `docs/design-docs/design.md`

- 总体架构 → 搜 `总体架构`
- 业务模块划分 → 搜 `业务模块划分`
- 数据模型 → 搜 `数据模型`
- 暂不考虑 → 搜 `暂不考虑`

### `docs/design-docs/api.md`

- 前端接口总览 → 搜 `范围与约定` 或 `API 归属`
- 认证相关路由归属 → 搜 `HITL 确认 API`、`WebSocket / STOMP API`、`非前端 API`

## Plan of Work

先实现基础设施，再把它挂到应用层。这样做的原因是认证模块既要被 REST controller 使用，也要被 WebSocket/STOMP 和 service 层使用；如果先做 app 接线，后面很容易把 token 解析、cookie 操作和上下文读取散成三套逻辑。

第一阶段会在 `pixflow-infra-auth` 内建立完整的认证核心：用户表读写、密码 hash、JWT 工具、refresh session store、黑名单 store、登录失败计数器和账号状态校验。这里的目标不是一次做完所有 UI，而是先让认证原语稳定可测。

第二阶段会把这些原语接入 `pixflow-app`：新增 `/api/auth/*` 控制器、统一异常映射、匿名放行规则、STOMP CONNECT 鉴权拦截器，以及 `GET /api/auth/me` 的恢复登录态接口。此时前端还不需要改动业务页面，但已经能通过标准接口恢复登录状态。

第三阶段是验证和收尾：补测试、跑集成验证、确认登出黑名单和 refresh 轮换按预期工作，并把后续要消费 `AuthPrincipal` 的业务模块边界写清楚，避免 auth 计划反向侵入业务模块实现细节。

## Concrete Steps

在仓库根目录 `D:\study\PixFlow` 执行。

1. 先在父 `pom.xml` 和模块依赖图里加入 `pixflow-infra-auth`，并创建模块目录结构。

   预期结果：

       [INFO] Building PixFlow ...
       [INFO] Reactor Build Order:
       ...
       pixflow-infra-auth

2. 在 `pixflow-infra-auth/src/main/java` 下建立认证核心包，按职责拆成 `config`、`crypto`、`token`、`session`、`context`、`filter`、`throttle`、`error`。

   预期结果：

       src/main/java/com/pixflow/infra/auth/...

3. 在 `pixflow-app/src/main/java/com/pixflow/app/auth` 下暴露对外 controller 与 STOMP 鉴权接入点。

   预期结果：

       /api/auth/register
       /api/auth/login
       /api/auth/refresh
       /api/auth/logout
       /api/auth/me

4. 补测试并运行模块级验证。

   预期结果：

       mvn -pl pixflow-infra-auth -am test

5. 在联调环境里验证前端登录态恢复、refresh 轮换、logout 失效和 WebSocket 鉴权拒绝。

## Validation and Acceptance

验收标准不是“代码能编译”，而是以下行为都能被实际观察到。

- 注册成功后，数据库里生成新用户，返回 access token 和 refresh cookie。
- 登录成功后，刷新页面还能通过 `/api/auth/me` 恢复当前用户。
- access token 过期后，带 refresh cookie 的 `/api/auth/refresh` 能换发新 access token。
- logout 后，旧 access token 立即被黑名单拒绝，refresh cookie 失效。
- 账号被禁用后，登录和刷新都返回拒绝。
- 登录失败超过阈值后，返回 429，而不是继续尝试。
- STOMP CONNECT 使用无效 token 时被拒绝，不能进入订阅通道。

测试层面至少要覆盖：

- 注册 / 用户名重复 / 密码 hash 校验
- 登录成功 / 密码错误 / 失败限流
- refresh 成功 / refresh 轮换 / refresh 失效
- logout 后 blacklist 生效
- `me` 接口在过期 token 和禁用账号下的返回
- `@CurrentUser` / `AuthContextHolder` 的解析行为
- Redis TTL 和会话回收

## Idempotence and Recovery

这份计划里的大多数步骤可以重复执行。

- 新建模块和目录是幂等的，重复执行只会覆盖同名文件。
- 测试可以反复跑，不会改变业务数据。
- 认证表结构如果需要迁移，必须采用加字段、加索引、兼容旧读路径的方式，不做破坏性删改。
- 如果 refresh 会话或黑名单设计在联调中发现与现有缓存封装不一致，优先改适配层，不直接把业务逻辑写进 controller。

## Artifacts and Notes

### 推荐的实现骨架

`pixflow-infra-auth` 内建议至少有以下抽象：

    AuthProperties
    PasswordHasher
    JwtTokenService
    AuthSessionStore
    RedisAuthSessionStore
    AuthContextHolder
    CurrentUserResolver
    JwtAuthenticationFilter
    LoginThrottleService
    AuthErrorCode

`pixflow-app` 侧建议至少有以下入口：

    AuthController
    AuthWebSocketInterceptor

### 期望的接口行为

    POST /api/auth/login
    -> 200 OK
    -> Set-Cookie: PIXFLOW_REFRESH=...
    -> body contains accessToken

    POST /api/auth/logout
    -> 204 No Content
    -> refresh cookie cleared

## Interfaces and Dependencies

### 关键依赖

- `pixflow-common`：统一错误码、异常、脱敏与基础响应体。
- `pixflow-contracts`：如果后续需要把认证相关 token/claim/store 契约抽成零依赖接口，应优先放在这里，但本计划默认先不强制新增，除非实现中确实需要打破模块环。
- `pixflow-infra-cache`：Redis 访问、TTL、计数器、黑名单、refresh 会话存储。
- `spring-security-core` / `spring-security-web`：过滤链、`SecurityContextHolder`、认证上下文。
- `pixflow-app`：对外 REST 控制器和 STOMP 接入。

### 需要最终存在的类型和职责

在 `pixflow-infra-auth` 中，最终应至少存在以下职责：

- `JwtTokenService`：签发、解析、验签、检查过期，并从 access token 中提取 `AuthPrincipal` 所需字段。
- `AuthSessionStore`：创建、轮换、校验和撤销 refresh session。
- `LoginThrottleService`：按 username 和 IP 维护失败计数并做限流决策。
- `AuthContextHolder`：在请求线程内保存当前用户身份。
- `CurrentUserResolver`：把认证信息注入 controller 方法参数。
- `JwtAuthenticationFilter`：在 Spring Security chain 中完成 access token 认证。

`pixflow-app` 中需要最终存在的外部入口：

- `AuthController`：注册、登录、刷新、登出、`me`。
- `AuthWebSocketInterceptor`：STOMP CONNECT 鉴权。

## Revision Notes

2026-07-01 / Codex: 新建 `infra/auth` ExecPlan，按 `docs/design-docs/infra/auth.md` 的单机账号体系设计补齐自包含执行计划，并显式加入文档定位关键词、实现机制、验证路径与恢复策略。该计划默认先做认证底座，再做 `pixflow-app` 接线，避免把认证逻辑散落在 controller 中。
