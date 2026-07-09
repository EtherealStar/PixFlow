# 按代码审查报告重构 Auth 安全默认值、Token 生命周期、限流与契约边界

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文面向完全不了解当前会话的执行者：只要有当前工作树、这份计划和代码审查报告，就应能完成 `pixflow-infra-auth` 的安全重构、验证结果，并知道为什么这样做。

## Purpose / Big Picture

这份计划要按代码审查报告修复 `pixflow-infra-auth` 的认证安全和运行契约问题。完成后，PixFlow 在缺少安全配置时会启动失败，不会用公开默认 JWT 密钥发 token；`/api/auth/me` 等已登录接口会正确经过 JWT 过滤器；refresh token 轮换具备单次消费语义；登录限流不能被并发或“同 IP 任意账号登录成功”绕过；JWT、DTO、SPI 和数据库 schema 会在边界处拒绝非法状态，而不是把错误留到运行时随机暴露。

本计划不是给旧实现补兼容分支。凡是审查报告指出的旧路径本身就是风险来源的，都要删除或替换：删除生产可用的默认 JWT secret，删除全 `/api/auth/**` 放行和全路径过滤器跳过，删除 refresh token 的 `find -> validate -> delete -> issue` 非原子轮换，删除登录限流的“先查后增”窗口和成功登录清空 IP 计数，删除 DTO/token record 的无校验构造，删除 access denied 误报为 invalid token 的语义。不为“旧测试方便”保留弱安全路径。

## Progress

- [x] (2026-07-09 22:20+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md` 和当前 active exec plans，确认现有 active plans 不专门覆盖 `pixflow-infra-auth` 的 JWT、refresh、限流与配置硬化。
- [x] (2026-07-09 22:30+08:00) 阅读 `docs/design-docs/infra/auth.md`、`docs/design-docs/design.md` 和 `docs/design-docs/infra/permission.md`，确认 auth 的边界是“认证与当前用户”，permission 的边界是“动作授权与确认令牌”，两者不能混淆。
- [x] (2026-07-09 22:40+08:00) 阅读代码审查报告，归纳出配置默认值、路径匹配、JWT 解析、refresh 轮换、登录限流、错误语义、DTO/SPI 合同、schema 和 POM 版本九组问题。
- [x] (2026-07-09 22:50+08:00) 核对热点代码：`AuthProperties`、`AuthAutoConfiguration`、`JwtAuthenticationFilter`、`AuthService`、`JwtTokenService`、`LoginThrottleService`、`AuthSessionStore`、`RedisAuthSessionStore`、`AccessTokenBlacklist`、`AuthErrorCode`、`V1__create_user_account.sql` 和 `pixflow-infra-cache` 的 `CacheStore.consume` / `AtomicCounter`。
- [x] (2026-07-09 23:05+08:00) 新建本中文 ExecPlan，明确要实现的机制、实施顺序、验证命令和旧风险路径清理标准。
- [x] (2026-07-09 21:38+08:00) 实施配置 fail-fast、JWT secret 强度校验、refresh cookie secure 默认策略和 Bean Validation 测试。
- [x] (2026-07-09 21:38+08:00) 实施 auth 路径规则、JWT 过滤器 servlet-path 匹配、`/api/auth/me` 鉴权和 access denied 错误语义修复。
- [x] (2026-07-09 21:38+08:00) 实施 JWT header/claim 校验、token record/DTO 红action、`SecurityErrorWriter` committed guard 和 `AuthContextHolder` authenticated guard。
- [x] (2026-07-09 21:38+08:00) 实施 refresh token 原子 consume、注册 duplicate-key 转换和登录限流原子化。
- [x] (2026-07-09 21:38+08:00) 实施 SPI 输入合同、数据库约束、设计文档同步和完整验证。

## Surprises & Discoveries

- Observation: `CacheStore` 已经有 `consume(CacheKey, Class<T>)`，`RedissonCacheStore` 使用 `RBucket.getAndDelete()` 实现原子读取删除；但 `AuthSessionStore` 当前没有暴露 consume，`AuthService.refresh` 仍使用 `find` 后 `delete`。
  Evidence: `pixflow-infra-cache/src/main/java/com/pixflow/infra/cache/store/CacheStore.java` 有 `consume`，`RedissonCacheStore.consume` 调 `getAndDelete()`；`AuthService.refresh` 调 `sessionStore.find(parts.jwtId())` 后再 `sessionStore.delete(parts.jwtId())`。

- Observation: `AtomicCounter.incrementBy` 是 Redis 脚本原子自增并首次设置 TTL；登录限流的问题主要在 auth 层把“是否允许”和“记录失败”拆成两步，并且成功登录会清掉 IP 计数。
  Evidence: `RedissonAtomicCounter.INCREMENT_WITH_INITIAL_TTL` 原子 `INCRBY + PEXPIRE`；`LoginThrottleService.assertAllowed` 先 `get`，`recordFailure` 后 `incrementBy`，`clear` 同时 reset username 和 IP key。

- Observation: `infra/auth.md` 的旧文字写“默认放行 `/api/auth/**`”，但当前代码审查已经证明这与 `/api/auth/me` 的已登录语义冲突。
  Evidence: `infra/auth.md` 第八节把 `/api/auth/**` 列为默认放行；当前 `AuthAutoConfiguration` 和 `JwtAuthenticationFilter.shouldNotFilter` 也按该口径实现，导致 `/api/auth/me` 无法从 access token 注入 `AuthPrincipal`。

- Observation: 父 POM 当前仍是 `1.0.0-SNAPSHOT`，审查报告要求 `pixflow-infra-auth/pom.xml` 使用非 SNAPSHOT parent 与仓库开发态存在冲突。
  Evidence: `pixflow-infra-auth/pom.xml` parent version 为 `1.0.0-SNAPSHOT`；另一个 active upload/loop plan 已记录同类 POM 版本审查规则与根项目开发态冲突。

## Decision Log

- Decision: `AuthProperties` 不再提供生产可用的 JWT secret 默认值；缺 secret、空 secret 或 UTF-8 长度小于 32 字节时启动失败。
  Rationale: HMAC JWT 的安全性完全依赖 secret。已知默认值会把“配置漏填”变成“攻击者可猜签名密钥”的生产事故。fail-fast 比带弱默认值启动更符合 `infra/auth.md` 的 fail-closed 原则。
  Date/Author: 2026-07-09 / Codex

- Decision: refresh cookie 默认应安全；允许开发环境关闭 Secure 必须通过显式配置或 profile gate，而不是隐式 `false`。
  Rationale: refresh token 是长期凭据，设计文档要求 HttpOnly + SameSite，并说明生产必须 Secure。默认 insecure 容易在非开发环境静默发送明文 HTTP cookie。
  Date/Author: 2026-07-09 / Codex

- Decision: auth 公开端点白名单只包含真正匿名入口，不再全 `/api/auth/**` 放行或过滤器跳过。
  Rationale: `/api/auth/me` 是已登录恢复态接口，必须经过 JWT 过滤器。全前缀放行使 controller 收不到 `AuthPrincipal`，也让 future auth 子接口更容易误暴露。
  Date/Author: 2026-07-09 / Codex

- Decision: refresh token 轮换必须使用原子 consume 语义，只有成功消费旧 session 的请求才可以签发新 token。
  Rationale: refresh token 是单次轮换凭据。并发请求若都能 `find` 到旧 session，再分别 `delete` 和 `issue`，就会产生多个新 refresh token，破坏重放检测。
  Date/Author: 2026-07-09 / Codex

- Decision: 登录失败限流使用“失败时原子递增并按返回值判定是否锁定”的机制，成功登录只清用户名计数，不清 IP 计数。
  Rationale: 并发暴力尝试会同时通过先查阈值再增的窗口；任意账号成功登录即可清掉 IP 计数会让攻击者重置 IP 级防护。用户名计数和 IP 计数的生命周期应独立。
  Date/Author: 2026-07-09 / Codex

- Decision: access denied 使用新的 `AUTH_ACCESS_DENIED` 或等价 forbidden 错误码，不能复用 `AUTH_TOKEN_INVALID`。
  Rationale: “token 无效”和“token 有效但权限不足”会驱动客户端做不同动作。前者可能触发重新登录或丢弃 token，后者应展示无权限并保留登录态。
  Date/Author: 2026-07-09 / Codex

- Decision: public record、SPI 和工具函数在构造/入口处拒绝非法状态。
  Rationale: `AccessTokenClaims`、`IssuedAccessToken`、`RefreshToken`、`AuthSessionStore`、`AccessTokenBlacklist` 等跨模块边界被下游当作可信数据。构造期或入口期失败比后续 NPE、错 TTL 或 malformed key 更安全、更可测。
  Date/Author: 2026-07-09 / Codex

- Decision: POM parent SNAPSHOT 暂不在本计划中硬改为不存在的 release 版本；先记录冲突，除非根项目发布 `1.0.0` 或用户明确要求统一版本。
  Rationale: 当前根项目仍是开发态 SNAPSHOT。单独把子模块 parent 改成 `1.0.0` 会在本地仓库没有 release parent 时破坏构建。版本发布是仓库级决策，不应在 auth 安全修复中伪造。
  Date/Author: 2026-07-09 / Codex

## Outcomes & Retrospective

本轮已完成 `pixflow-infra-auth` 安全硬化实现。主要结果：

- `AuthProperties` 删除生产可用默认 JWT secret，启用 Bean Validation，JWT secret 必填且 UTF-8 至少 32 字节，refresh cookie 默认 Secure。
- `AuthAutoConfiguration` 与 `JwtAuthenticationFilter` 的匿名入口收窄到 `POST /api/auth/register`、`POST /api/auth/login`、`POST /api/auth/refresh`；`/api/auth/me` 与 logout 等已登录接口会经过 JWT 过滤器；过滤器改用 servlet path/pathInfo 匹配，并让标准 `Authorization` 头优先于 `X-Auth-Token` fallback。
- `AuthErrorCode` 增加 `AUTH_ACCESS_DENIED`，access denied 返回 403；`SecurityErrorWriter` 已提交响应时不再二次写；`AuthContextHolder` 要求 authentication 已认证。
- `JwtTokenService` 构造期校验强 secret，解析时校验 JWT header `alg=HS256` / `typ=JWT`、签名、issuer、token type 和必填 claims；缺失/格式错误/时间顺序非法均转 `AUTH_TOKEN_INVALID`；过期 token 剩余 TTL 返回 0。
- `AuthSessionStore` 增加 `consume`，`RedisAuthSessionStore` 使用 `CacheStore.consume`，`AuthService.refresh` 改为单次消费旧 refresh session 后再签发新 token；并发同 token refresh 只有一个成功。
- `LoginThrottleService` 改为失败时原子递增 username/IP 计数并按返回值判定锁定；登录成功只清 username 计数，不清 IP 计数。
- public record / DTO / SPI 边界增加非法状态校验和敏感值 `toString()` redaction；注册捕获 duplicate key 并转 `AUTH_USERNAME_TAKEN`。
- `V1__create_user_account.sql` 增加 `status` check，并把审计时间列改为 `TIMESTAMP(3)`；`docs/design-docs/infra/auth.md` 已同步新的安全口径。

验证结果：

    mvn -pl pixflow-infra-auth -am test

结果：BUILD SUCCESS。`pixflow-infra-auth` 33 个测试通过；上游 `common` / `infra-cache` 测试也通过。测试过程中 `RedisIntegrationTest.cacheStoreDegradesInvalidJsonToMiss` 按预期打印了一段 invalid JSON 降级 warning，不影响结果。

    mvn -pl pixflow-app -am -DskipTests compile

结果：BUILD SUCCESS。30 个 reactor 模块编译通过。

收尾检查：

    rg -n 'pixflow-dev-secret-change-me|requestMatchers\("/api/auth/\*\*"\)|path\.startsWith\("/api/auth/"\)|getRequestURI\(\)|sessionStore\.find\(parts\.jwtId\(\)\)|Math\.max\(1, seconds\)|String\.valueOf\(payload\.get\(|counter\.reset\(namespace\.key\("auth", "fail-ip"' pixflow-infra-auth\src\main\java

结果：无命中，说明本计划列出的旧生产风险路径已清除。

    rg -n 'password=<redacted>|accessToken=<redacted>|refreshToken=<redacted>' pixflow-infra-auth\src\main\java pixflow-infra-auth\src\test\java
    rg -n 'AUTH_ACCESS_DENIED|consume\(' pixflow-infra-auth\src\main\java pixflow-infra-auth\src\test\java

结果：命中 DTO redaction、新 forbidden 错误码和 refresh consume 路径，符合预期。

POM parent 仍保留 `1.0.0-SNAPSHOT`，原因同 Decision Log：根项目仍处开发态，当前没有可引用的 release parent，不在 auth 安全修复中伪造版本。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。本计划聚焦 Maven 模块 `pixflow-infra-auth`，必要时会小范围触达 `pixflow-infra-cache` 的接口测试，但不改变 permission、conversation、agent 等上层业务机制。

`pixflow-infra-auth` 是认证基础设施模块，负责账号注册登录、JWT access token、refresh token 会话、access token 黑名单、登录失败限流、Spring Security 过滤链、当前用户上下文和 `@CurrentUser` 参数解析。它回答“当前请求是谁、token 是否有效、账号是否可用”。动作级授权、HITL 确认和确认令牌属于 `harness/permission`，不在本计划中实现。

关键文件如下：

- `pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/config/AuthProperties.java`：认证配置绑定。当前有已知 JWT secret 默认值，缺少 Bean Validation。
- `pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/config/AuthAutoConfiguration.java`：Spring Security chain、bean 装配和 endpoint permit rules。当前全 `/api/auth/**` 放行，access denied 误用 `AUTH_TOKEN_INVALID`。
- `pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/filter/JwtAuthenticationFilter.java`：JWT 提取与认证注入。当前用 `getRequestURI()` 做跳过判断，并跳过所有 `/api/auth/`。
- `pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/service/AuthService.java`：注册、登录、刷新、登出和 access token 认证主流程。当前注册查重非原子，refresh 轮换非原子。
- `pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/token/JwtTokenService.java`：JWT 签发、解析和 TTL 计算。当前未校验 JWT header，缺失 claim 可能转成 `"null"` 或 unchecked 异常，expired TTL 被 clamp 到 1 秒。
- `pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/throttle/LoginThrottleService.java`：登录失败计数。当前 assert 与 record 分离，成功登录清掉 IP counter。
- `pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/session/AuthSessionStore.java` 和 `RedisAuthSessionStore.java`：refresh session 存储。当前缺少 consume 合同和 invalid input 合同。
- `pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/session/AccessTokenBlacklist.java` 和 `RedisAccessTokenBlacklist.java`：access token 黑名单。当前缺少 jwtId / ttl 输入合同。
- `pixflow-infra-auth/src/main/resources/db/migration/V1__create_user_account.sql`：账号表 schema。当前 `status` 无 check 约束，时间列使用 MySQL `DATETIME(3)`。

这里的“原子 consume”指在存储层一次性读取并删除 refresh session。Redis 实现可以用 `GETDEL` 或 Redisson `getAndDelete()`；Java 层先 `find` 再 `delete` 不是原子 consume。

这里的“fail-fast 配置”指 Spring 应用在启动期发现安全配置缺失或非法就直接启动失败，而不是在第一次请求时出错，更不能带弱默认值继续运行。

## Plan of Work

第一阶段修复配置安全默认值和启动期校验。修改 `AuthProperties`，加 `@Validated`，对嵌套配置加 `@Valid`。`jwt.issuer`、`refresh.cookieName`、`refresh.cookiePath`、`refresh.cookieSameSite` 必须非空；`jwt.secret` 不再有公开默认值，必须非空且 UTF-8 字节长度至少 32；`jwt.accessTtl`、`refresh.ttl`、`throttle.window`、`throttle.blockTtl` 必须为正；`jwt.clockSkew` 不为负；`password.bcryptStrength` 限制在应用策略范围，建议 10..14，如果要保留 BCrypt 支持全范围则至少 4..31；`throttle.maxFailures` 必须为正。refresh cookie 的 `cookieSecure` 默认改为 true；若为了本地开发需要 false，必须在 `application-dev.yml` 或测试配置显式写出。同步更新 `docs/design-docs/infra/auth.md` 的配置示例，说明生产必须设置 `PIXFLOW_AUTH_JWT_SECRET`。

第二阶段在加密和 token 服务处做防御性校验。`PasswordHasher` 构造函数即使上游已校验，也要拒绝不合法 strength。`JwtTokenService` 构造或 `sign` 前缓存并校验 secret，拒绝 blank 或小于 32 字节。`JwtTokenService.issue` 拒绝空 username；`IssuedAccessToken`、`RefreshToken` 和 `AccessTokenClaims` 增 compact constructor，拒绝 null/blank token、jwtId、username，并要求 `expiresAt` 晚于 `issuedAt`。这些校验应有单元测试，证明直接 new 非法 record 会失败。

第三阶段修复 Spring Security 端点白名单和过滤器路径。`AuthAutoConfiguration` 的 permit rules 改为只放行匿名入口：`POST /api/auth/register`、`POST /api/auth/login`、`POST /api/auth/refresh`，以及 health、ws、静态资源和 OPTIONS。`/api/auth/me`、`/api/auth/logout` 等需要当前用户或 access token 的接口不再 permitAll。`JwtAuthenticationFilter.shouldNotFilter` 使用 servlet path 加 pathInfo，而不是 `getRequestURI()`，避免部署在 context path 下时匹配失效。过滤器跳过规则同样只跳过真正匿名入口；已登录 auth endpoint 必须尝试解析 Bearer token。

第四阶段修复 token 提取语义。`JwtAuthenticationFilter.extractToken` 让 `Authorization` 头成为权威来源：如果存在 `Authorization` 且以 `Bearer ` 开头，取 Bearer token；如果存在 `Authorization` 但不是 Bearer，返回 null 或抛 `AUTH_TOKEN_INVALID`，但不能再回退到 `X-Auth-Token`。只有完全没有 `Authorization` 时才读取 `X-Auth-Token`。为兼容前端或 WebSocket 限制，`X-Auth-Token` 保留为 fallback，但不会覆盖或绕过标准头。

第五阶段修复错误语义和响应写入。`AuthErrorCode` 增加 `AUTH_ACCESS_DENIED`，类别为 `PERMISSION`，HTTP 为 403。`AuthAutoConfiguration` 的 `AccessDeniedHandler` 使用该错误码。`SecurityErrorWriter.write` 开头检查 `response.isCommitted()`，已提交则直接返回，避免二次写响应掩盖原始错误。`AuthContextHolder.current()` 必须要求 `authentication.isAuthenticated()`，避免未认证但携带 principal 的中间对象被当作当前用户；如将来 anonymous principal 可适配为 `AuthPrincipal`，也要排除 anonymous authentication。

第六阶段收紧 JWT 解析。`JwtTokenService.parse` 先 decode header，要求 `alg == HS256`、`typ == JWT`，再验签。payload 中 `iss`、`typ`、`sub`、`uname`、`jti`、`iat`、`exp` 都显式校验：缺失、blank、非数字、时间顺序非法、`expiresAt` 不晚于 `issuedAt` 都转为 `AuthException(AUTH_TOKEN_INVALID)`，不能产生 `NumberFormatException`、`String.valueOf(null)` 或 `"null"` claim。`remainingTtlSeconds` 对已过期 token 返回 0，不再强制 1 秒。

第七阶段让 refresh token 轮换原子化。扩展 `AuthSessionStore`，新增 `Optional<AuthSession> consume(String refreshJwtId)`，Javadoc 写明 refreshJwtId 必须非空非 blank；`RedisAuthSessionStore.consume` 调 `CacheStore.consume(namespace.key("auth","refresh", refreshJwtId), AuthSession.class)`。`AuthService.refresh` 改为先 `consume(parts.jwtId())`，只有 consume 命中的请求继续校验 token hash、过期时间、用户状态并签发新 token。token hash 校验失败时不再需要额外 delete，因为 session 已被消费；这也能把 replay 统一变为 expired/invalid。单测要用并发请求证明同一个 refresh token 只有一个成功。

第八阶段修复注册和服务入口验证。`AuthService.register` 和 `login` 在读取 record 字段前先显式拒绝 null request，转换为 `AuthException` 的 validation 错误。注册不依赖单独查重保证唯一性：保留前置查重用于友好错误，但 `userMapper.insert(entity)` 周围捕获唯一键冲突，统一转 `AUTH_USERNAME_TAKEN`。这样两个并发相同 username 注册时，一个成功，一个得到结构化 username taken，而不是数据库异常。

第九阶段重构登录限流。当前最小可落地方案是把 `LoginThrottleService.recordFailure` 改为返回当前 username/IP 失败计数，并在失败记录之后判断是否达到阈值；`AuthService.login` 在密码错误时调用该方法，达到阈值就抛 `AUTH_TOO_MANY_ATTEMPTS`，未达到阈值抛 `AUTH_INVALID_CREDENTIALS`。更完整方案是给 `LoginThrottleService` 增加 `recordFailureAndAssert`，内部对 username 和 IP key 分别 `incrementBy(..., 1, blockTtl)` 或使用专门的 block key，以返回值决定锁定。`assertAllowed` 只检查已经存在的 block/计数状态；成功登录只 reset username key，不 reset IP key。`blockTtl` 和实际 enforcement TTL 必须一致：要么失败计数 key 用 blockTtl，要么新增 `auth:block:{username/ip}` key 并用 blockTtl；不能继续用 window enforcing、blockTtl reporting。

第十阶段明确 SPI 和工具函数的 invalid input 合同。`AuthSessionStore.save` 要求 session 非 null、ttl 非 null 且正；`find`、`consume`、`delete` 要求 refreshJwtId 非 null 非 blank。`AccessTokenBlacklist.revoke` 要求 jwtId 非 blank、ttl 正；若 ttl 为 0，调用方不应写黑名单，可在 `AuthService.logout` 中只在 `remainingTtlSeconds > 0` 时 revoke。`AccessTokenBlacklist.isRevoked` 要求 jwtId 非 blank。`RedisAuthSessionStore`、`RedisAccessTokenBlacklist` 入口处执行相同校验，不能依赖 cache key 拼接失败。`TokenHashing.sha256` 对 null 输入抛 `IllegalArgumentException` 或明确的 `NullPointerException`，不要包装成 generic hashing failure。

第十一阶段隐藏敏感值。`LoginRequest.toString()` 返回用户名和 `<redacted>` password；`AuthTokenResponse.toString()` 对 accessToken 和 refreshToken 红action，只保留过期时间和 user 摘要。若 `RegisterRequest` 中包含 password，也要红action。测试应断言 `toString()` 不包含原始密码、access token 或 refresh token。

第十二阶段修复数据库 schema。因为项目仍处开发阶段，直接更新 `pixflow-infra-auth/src/main/resources/db/migration/V1__create_user_account.sql`：`status` 加 `CHECK (status IN ('ACTIVE','DISABLED'))`；`created_at`、`updated_at`、`last_login_at`、`password_updated_at` 改为 `TIMESTAMP(3)`，并在文档里明确应用/JDBC 以 UTC 解释 `Instant`。如果 MySQL 版本或兼容性需要保守做法，可保留 `DATETIME(3)` 但必须在数据源层统一 UTC 并在 plan decision 中记录理由；默认推荐 TIMESTAMP。

第十三阶段处理 POM 版本审查项。先检查根 `pom.xml` 是否已经有非 SNAPSHOT release parent 可引用。如果根项目仍是 `1.0.0-SNAPSHOT`，不要单独把 `pixflow-infra-auth/pom.xml` 改成不存在的 `1.0.0`；在本计划 `Surprises & Discoveries` 和 `Outcomes & Retrospective` 记录“审查规则与当前开发态冲突”。如果用户或后续发布流程把根版本切到 release，则同步移除子模块 SNAPSHOT parent。

第十四阶段更新设计文档。修改 `docs/design-docs/infra/auth.md`：默认放行不再写 `/api/auth/**`，改为 register/login/refresh 等匿名入口；说明 `/api/auth/me` 需要 access token；配置示例里 JWT secret 无默认值，refresh cookie secure 生产默认 true；refresh 采用 Redis atomic consume；登录限流采用 failure increment 返回值和 username/IP 双维度，成功登录不清 IP 计数；access denied 使用 403。

## Concrete Steps

所有命令从仓库根目录 `D:\study\PixFlow` 执行。

先确认旧风险路径：

    rg -n "pixflow-dev-secret-change-me|requestMatchers\\(\"/api/auth/\\*\\*\"\\)|path\\.startsWith\\(\"/api/auth/\"\\)|getRequestURI\\(\\)|sessionStore\\.find\\(parts\\.jwtId\\(\\)\\)|sessionStore\\.delete\\(parts\\.jwtId\\(\\)\\)|counter\\.reset\\(namespace\\.key\\(\"auth\", \"fail-ip\"|Math\\.max\\(1, seconds\\)|String\\.valueOf\\(payload\\.get\\(\" pixflow-infra-auth/src/main/java

实施完成后，同一命令不应在生产 Java 中命中旧风险路径。允许测试中出现“旧行为不存在”的断言，不允许生产实现继续包含这些路径。

第一组修改配置和加密边界：

    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/config/AuthProperties.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/crypto/PasswordHasher.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/token/JwtTokenService.java

新增或更新测试：

    pixflow-infra-auth/src/test/java/com/pixflow/infra/auth/AuthPropertiesValidationTest.java
    pixflow-infra-auth/src/test/java/com/pixflow/infra/auth/PasswordHasherTest.java
    pixflow-infra-auth/src/test/java/com/pixflow/infra/auth/JwtTokenServiceTest.java

第二组修改过滤链和错误语义：

    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/config/AuthAutoConfiguration.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/filter/JwtAuthenticationFilter.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/filter/SecurityErrorWriter.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/context/AuthContextHolder.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/error/AuthErrorCode.java

新增或更新测试：

    pixflow-infra-auth/src/test/java/com/pixflow/infra/auth/JwtAuthenticationFilterTest.java
    pixflow-infra-auth/src/test/java/com/pixflow/infra/auth/AuthSecurityFilterChainTest.java
    pixflow-infra-auth/src/test/java/com/pixflow/infra/auth/SecurityErrorWriterTest.java
    pixflow-infra-auth/src/test/java/com/pixflow/infra/auth/AuthContextHolderTest.java

第三组修改 refresh、注册和 DTO：

    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/service/AuthService.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/session/AuthSessionStore.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/session/RedisAuthSessionStore.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/token/AccessTokenClaims.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/token/IssuedAccessToken.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/token/RefreshToken.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/service/LoginRequest.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/service/RegisterRequest.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/service/AuthTokenResponse.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/service/UserView.java

新增或更新测试：

    pixflow-infra-auth/src/test/java/com/pixflow/infra/auth/AuthServiceTest.java
    pixflow-infra-auth/src/test/java/com/pixflow/infra/auth/AuthServiceRefreshConcurrencyTest.java
    pixflow-infra-auth/src/test/java/com/pixflow/infra/auth/AuthRecordContractsTest.java
    pixflow-infra-auth/src/test/java/com/pixflow/infra/auth/AuthSensitiveToStringTest.java

第四组修改限流和 SPI：

    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/throttle/LoginThrottleService.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/session/AccessTokenBlacklist.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/session/RedisAccessTokenBlacklist.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/session/TokenHashing.java

新增或更新测试：

    pixflow-infra-auth/src/test/java/com/pixflow/infra/auth/LoginThrottleServiceTest.java
    pixflow-infra-auth/src/test/java/com/pixflow/infra/auth/AuthSessionStoreContractTest.java
    pixflow-infra-auth/src/test/java/com/pixflow/infra/auth/AccessTokenBlacklistContractTest.java

第五组修改 schema 和设计文档：

    pixflow-infra-auth/src/main/resources/db/migration/V1__create_user_account.sql
    docs/design-docs/infra/auth.md

运行模块验证：

    mvn -pl pixflow-infra-auth -am test
    mvn -pl pixflow-infra-cache,pixflow-infra-auth -am test
    mvn -pl pixflow-app -am -DskipTests compile

如只修改 auth 且 cache 测试耗时，可先运行：

    mvn -pl pixflow-infra-auth -am test

收尾检查：

    rg -n "pixflow-dev-secret-change-me|requestMatchers\\(\"/api/auth/\\*\\*\"\\)|path\\.startsWith\\(\"/api/auth/\"\\)|getRequestURI\\(\\)|sessionStore\\.find\\(parts\\.jwtId\\(\\)\\)|Math\\.max\\(1, seconds\\)|String\\.valueOf\\(payload\\.get\\(|counter\\.reset\\(namespace\\.key\\(\"auth\", \"fail-ip\"" pixflow-infra-auth/src/main/java
    rg -n "password=<redacted>|accessToken=<redacted>|refreshToken=<redacted>" pixflow-infra-auth/src/main/java pixflow-infra-auth/src/test/java
    rg -n "AUTH_ACCESS_DENIED|consume\\(" pixflow-infra-auth/src/main/java pixflow-infra-auth/src/test/java

第一条不应命中生产旧风险路径；第二条应命中 DTO redaction 实现或测试；第三条应命中新 forbidden 错误码和 refresh session consume 路径。

## Validation and Acceptance

配置验收：未设置 `pixflow.auth.jwt.secret` 时，Spring context 启动失败并提示 JWT secret 缺失；设置短于 32 UTF-8 字节的 secret 时启动失败；设置合法 secret 后启动成功。`cookieSecure` 默认 true，测试 profile 要显式配置 false 才能得到非 Secure refresh cookie。

端点鉴权验收：`POST /api/auth/login` 带坏 Authorization header 不应被旧 access token 阻断匿名登录；`GET /api/auth/me` 无 token 返回 401，带合法 Bearer token 返回当前用户，带非法 token 返回 token invalid；部署 context path 为 `/pixflow` 时，`/pixflow/api/auth/login` 的匿名跳过仍按 servlet path 正常匹配。

错误语义验收：认证失败返回 401 的 token missing/invalid/expired/revoked；已认证但无权限的访问返回 403 `AUTH_ACCESS_DENIED`。客户端不应因为 403 丢弃有效 token。

JWT 验收：带 `alg=none`、`alg=HS512`、`typ` 非 JWT、缺 `sub`、`sub` 非数字、缺 `uname`、缺 `jti`、`exp <= iat`、issuer 错误的 token 都返回 `AuthException(AUTH_TOKEN_INVALID)`，不抛 unchecked exception。已过期 token 的 `remainingTtlSeconds` 返回 0。

Refresh 验收：同一个 refresh token 发起两个并发 refresh，只有一个请求返回新 token；另一个返回 refresh expired/invalid，且不会签发第二组 token。refresh token hash 不匹配时旧 session 已消费，不能继续重放。

注册验收：两个并发相同 username 注册，一个成功，一个得到 `AUTH_USERNAME_TAKEN`；不出现裸数据库异常或 500。

限流验收：并发错误密码尝试不能超过配置阈值太多；达到阈值后 username 或 IP 任一维度命中都会返回 429；同一 IP 登录另一个有效账号成功后，IP 失败计数仍保留，不能继续暴力破解其他账号。返回给用户的 lockout duration 与实际 Redis key TTL 语义一致。

DTO 和日志验收：`LoginRequest.toString()` 不包含明文 password；`RegisterRequest.toString()` 不包含明文 password；`AuthTokenResponse.toString()` 不包含 access token 或 refresh token。测试可以直接 assert 原始秘密字符串不在 toString 输出中。

SPI 合同验收：`AuthSessionStore.save(null, ttl)`、`save(session, Duration.ZERO)`、`find("")`、`consume(" ")`、`delete(null)`、`AccessTokenBlacklist.revoke("", ttl)`、`revoke(jwtId, Duration.ZERO)`、`isRevoked(" ")` 都按文档抛明确异常。Redis 实现与测试内存实现行为一致。

Schema 验收：空 dev DB 跑 Flyway 后，`user_account.status` 只能为 `ACTIVE` 或 `DISABLED`；audit 时间列与 `Instant` 映射不再存在无文档的 timezone 漂移风险。若最终保留 `DATETIME(3)`，必须有明确 UTC 数据源配置和测试说明。

## Idempotence and Recovery

本计划可以分阶段实施。每个阶段完成后运行对应测试，失败时不要恢复旧风险路径，而要沿新的安全边界修复调用点和测试替身。

配置改动可能导致现有测试 context 因缺 JWT secret 启动失败。正确处理方式是在测试配置中显式提供强 secret，不要把默认 secret 放回生产代码。

端点白名单收紧后，已有 controller 测试如果依赖 `/api/auth/**` 全放行，应更新为带合法 access token 或只调用匿名入口。不要为了旧测试恢复全前缀 permitAll。

Refresh consume 改动后，测试内存 `AuthSessionStore` 必须实现同样的单次消费语义。并发测试应使用线程安全 map 或同步块，不能用非线程安全 fake 掩盖竞态。

限流改动可能改变“第 N 次错误密码”返回的是 invalid credentials 还是 too many attempts。以新机制为准：达到或超过阈值的失败应返回 too many attempts，并且实际 enforcement TTL 与响应 TTL 对齐。

数据库 migration 当前处开发阶段，可直接更新 V1。若本地已有旧 dev DB，按开发流程重建或清理 Flyway 历史；不要为了旧库兼容删除 check 约束或继续使用弱 schema。

POM parent version 若暂不改，需要在最终回顾中明确说明原因和后续仓库级动作。不要在 auth 模块里引用一个本地不存在的 release parent。

## Artifacts and Notes

审查报告对应的最高风险证据：

    AuthProperties:
        private String secret = "pixflow-dev-secret-change-me-with-at-least-32-bytes";
        private boolean cookieSecure;

    AuthAutoConfiguration:
        .requestMatchers("/api/auth/**").permitAll()
        AUTH_TOKEN_INVALID used for access denied

    JwtAuthenticationFilter:
        String path = request.getRequestURI();
        path.startsWith("/api/auth/")
        Authorization non-Bearer falls back to X-Auth-Token

    AuthService.refresh:
        sessionStore.find(parts.jwtId())
        validate hash / expiry / user
        sessionStore.delete(parts.jwtId())
        issueTokens(entity)

    LoginThrottleService:
        assertAllowed uses counter.get
        recordFailure increments later
        clear resets auth:fail-ip

    JwtTokenService:
        no header alg/typ validation before trusting signed token
        String.valueOf(payload.get("uname"))
        Long.parseLong(String.valueOf(payload.get("sub")))
        Math.max(1, seconds)

目标机制摘要：

    startup:
        validated AuthProperties -> no default JWT secret -> strong secret -> secure refresh cookie default

    endpoint auth:
        anonymous endpoint whitelist -> servlet path matching -> JWT filter for /api/auth/me -> 401 vs 403 split

    jwt:
        decode header -> require HS256/JWT -> verify signature -> validate required claims -> construct validated claims

    refresh:
        parse refresh cookie -> AuthSessionStore.consume(jti) -> verify hash/expiry/user -> issue new pair

    throttle:
        check active block/counters -> failed login increments username/ip atomically -> threshold creates/enforces block -> success clears username only

    contracts:
        public records validate invariants -> SPI rejects invalid input -> redacted toString -> schema enforces enum/time assumptions

## Interfaces and Dependencies

`AuthSessionStore` 应新增 consume，并写清 invalid input 合同：

    public interface AuthSessionStore {
        void save(AuthSession session, Duration ttl);
        Optional<AuthSession> find(String refreshJwtId);
        Optional<AuthSession> consume(String refreshJwtId);
        void delete(String refreshJwtId);
    }

`RedisAuthSessionStore.consume` 应使用 cache 原子 consume：

    public Optional<AuthSession> consume(String refreshJwtId) {
        requireRefreshJwtId(refreshJwtId);
        return cacheStore.consume(namespace.key("auth", "refresh", refreshJwtId), AuthSession.class);
    }

`LoginThrottleService` 应提供一个失败记录并返回锁定状态的入口。具体命名可调整，但语义必须是失败计数和阈值判定不再由调用方拆散：

    public void assertAllowed(String username, String ipAddress);
    public void recordFailureAndAssert(String username, String ipAddress);
    public void clearUsername(String username);

如果引入 block key，应明确：

    auth:fail:{username}      failure window or block counter
    auth:fail-ip:{ipHash}     IP failure window or block counter
    auth:block:{username}     optional block key with blockTtl
    auth:block-ip:{ipHash}    optional block key with blockTtl

`AuthErrorCode` 应新增：

    AUTH_ACCESS_DENIED("AUTH_ACCESS_DENIED", ErrorCategory.PERMISSION, HttpStatus.FORBIDDEN)

`JwtTokenService` 内部应有必填 claim helpers：

    private static String asRequiredString(Object value, String claim)
    private static long asLong(Object value, String claim)
    private Map<String, Object> decodeJson(String part)
    private static void requireEquals(Object expected, Object actual, AuthErrorCode code, String message)

Public token records 应有 compact constructor：

    public record RefreshToken(String token, String jwtId) {
        public RefreshToken {
            if (token == null || token.isBlank()) throw new IllegalArgumentException("token must not be blank");
            if (jwtId == null || jwtId.isBlank()) throw new IllegalArgumentException("jwtId must not be blank");
        }
    }

    public record AccessTokenClaims(Long userId, String username, String jwtId, Instant issuedAt, Instant expiresAt) {
        public AccessTokenClaims {
            Objects.requireNonNull(userId, "userId must not be null");
            if (username == null || username.isBlank()) throw new IllegalArgumentException("username must not be blank");
            if (jwtId == null || jwtId.isBlank()) throw new IllegalArgumentException("jwtId must not be blank");
            Objects.requireNonNull(issuedAt, "issuedAt must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            if (!expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

`SecurityFilterChain` endpoint rules 应表达真实匿名入口：

    .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/refresh").permitAll()
    .requestMatchers("/actuator/health").permitAll()
    .requestMatchers("/ws", "/ws/**").permitAll()
    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
    .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
    .anyRequest().authenticated()

## Revision Notes

2026-07-09 / Codex: 新建本 ExecPlan。原因是代码审查报告指出 `pixflow-infra-auth` 存在安全敏感默认值、配置缺少校验、auth 端点和 JWT filter 规则冲突、refresh token 轮换非原子、登录限流可绕过、JWT 解析宽松、敏感 DTO 可泄漏、SPI 合同不清、schema 弱约束和 POM 版本卫生问题。计划选择按 `infra/auth.md` 的 fail-closed 原则收敛为启动期强校验、精确匿名端点白名单、servlet-path filter matching、JWT header/claim 严格校验、Redis atomic consume refresh、失败计数原子化、DTO redaction、SPI invalid input 合同和强 schema。POM parent SNAPSHOT 因根项目仍处开发态，暂记录为仓库级版本决策，不在 auth 安全修复中伪造 release parent。
