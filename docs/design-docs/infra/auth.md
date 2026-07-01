# infra/auth —— 单机账号体系与 JWT 鉴权（Wave 1 基础设施）

> 本文是 PixFlow 为“单机、简历展示、可落地”的账号体系补写的设计文档。它保留项目原本的单机部署前提，但补齐注册、登录、JWT 鉴权、刷新、登出、会话撤销和资源归属，避免把项目做成“只有页面没有身份”的演示壳。
> 范围：用户名密码注册 / 登录、JWT access token、Redis 会话与黑名单、当前用户上下文、Web/API/STOMP 鉴权、基础限流与账号归属。本文不做 SSO、OAuth2、MFA、组织 / 租户 / 复杂 RBAC。

---

## 目录

- [一、文档定位与设计原则](#一文档定位与设计原则)
- [二、与 permission 的边界](#二与-permission-的边界)
- [三、模块结构与依赖位置](#三模块结构与依赖位置)
- [四、账号模型与状态机](#四账号模型与状态机)
- [五、Token 模型](#五token-模型)
- [六、注册 / 登录 / 刷新 / 登出流程](#六注册--登录--刷新--登出流程)
- [七、Redis 键与 TTL](#七redis-键与-ttl)
- [八、鉴权过滤链与上下文](#八鉴权过滤链与上下文)
- [九、前端与 WebSocket 接入](#九前端与-websocket-接入)
- [十、错误处理、限流与安全](#十错误处理限流与安全)
- [十一、配置项](#十一配置项)
- [十二、对其他模块的契约](#十二对其他模块的契约)
- [十三、测试策略](#十三测试策略)
- [十四、暂不考虑](#十四暂不考虑)
- [Revision Notes](#revision-notes)

---

## 一、文档定位与设计原则

`infra/auth` 处于依赖 DAG 的 **Wave 1 基础设施**。它被 `pixflow-app` 的 Web 层、前端交互层和后续业务模块共同依赖，职责是把“用户是谁、是否登录、是否仍然有效”收敛成一组稳定原语，而不是把身份逻辑散落在 controller 里。

模块专属设计原则：

1. **单机优先**。本项目不做 SSO、OAuth2、企业级多租户，也不做跨系统单点登录。目标是本地可跑、结构完整、面试可讲。
2. **身份与权限分离**。`auth` 只回答“你是谁、token 是否有效”；`permission` 只回答“你能不能做这个动作”。前者是认证，后者是授权 / HITL。
3. **JWT 只承载短期访问态**。access token 设计为短 TTL、可自校验；refresh token 不做 JWT，采用 Redis 中的服务端会话，方便撤销和轮换。
4. **Redis 负责状态，MySQL 负责事实**。密码 hash、用户主体信息、账户状态落 MySQL；刷新会话、黑名单、失败计数、用户快照缓存放 Redis。
5. **请求失败要 fail-closed**。token 解析失败、refresh 会话缺失、账号禁用、登录风控命中，都应返回 401 / 403 / 429，而不是“放行再说”。
6. **资源归属必须有 owner**。凡是用户可见且会被修改的顶层业务对象，都要带 `owner_user_id`，否则登录只是在门口贴了个牌子，数据并没有隔离。

---

## 二、与 permission 的边界

`permission` 已经承担 HITL 硬边界和确认令牌。`auth` 不碰这些动作级规则。

| 能力 | 归属 | 例子 |
|---|---|---|
| 账户注册 / 登录 | `auth` | `POST /api/auth/register`、`POST /api/auth/login` |
| access token 校验 | `auth` | JWT 签名、过期、黑名单 |
| refresh 会话 | `auth` | Redis 中的刷新 token 会话 |
| 当前用户上下文 | `auth` | `AuthPrincipal`、`SecurityContext` |
| 动作级确认 / HITL | `permission` | `CONFIRM_REQUIRED`、确认令牌 |
| 工具副作用授权 | `permission` | `submit_image_plan`、`submit_imagegen_plan` |

一句话区分：

- `auth` 管“你是不是合法登录用户”。
- `permission` 管“合法用户现在能不能执行这个动作”。

---

## 三、模块结构与依赖位置

建议新增 Maven 模块 `pixflow-infra-auth`，源码包 `com.pixflow.infra.auth`。

```
infra/auth/
├── config/
│   ├── AuthProperties.java
│   └── AuthAutoConfiguration.java
├── crypto/
│   └── PasswordHasher.java
├── token/
│   ├── JwtTokenService.java
│   ├── AccessTokenClaims.java
│   └── RefreshTokenGenerator.java
├── session/
│   ├── AuthSessionStore.java
│   └── RedisAuthSessionStore.java
├── filter/
│   └── JwtAuthenticationFilter.java
├── context/
│   ├── AuthPrincipal.java
│   ├── AuthContextHolder.java
│   └── CurrentUserResolver.java
├── throttle/
│   └── LoginThrottleService.java
└── error/
    └── AuthErrorCode.java
```

对外控制器不放在 infra 内部，而是由 `pixflow-app` 暴露：

```
pixflow-app/src/main/java/com/pixflow/app/auth/
├── AuthController.java
└── AuthWebSocketInterceptor.java
```

依赖方向：

```
auth ──► common
auth ──► infra/cache
auth ──► spring-security-web / spring-security-core
pixflow-app ──► auth
module/* ──► auth
```

---

## 四、账号模型与状态机

### 4.1 MySQL：`user_account`

| 字段 | 说明 |
|---|---|
| `id` | 主键，建议 `BIGINT` |
| `username` | 登录名，唯一，建议只允许字母 / 数字 / 下划线 |
| `password_hash` | BCrypt hash，不存明文 |
| `display_name` | 展示名，可选 |
| `status` | `ACTIVE` / `DISABLED` |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |
| `last_login_at` | 最近登录时间 |
| `password_updated_at` | 最近改密时间 |

约束：

- `username` 大小写统一归一化。
- `username` 唯一索引必须存在。
- 密码只保存 hash，不保存原文和可逆密文。

### 4.2 用户状态机

```
NEW
  └─ register ─► ACTIVE
ACTIVE
  ├─ disable ─► DISABLED
  └─ password change ─► ACTIVE
DISABLED
  └─ enable ─► ACTIVE
```

### 4.3 顶层资源归属

以下顶层业务对象增加 `owner_user_id`：

- `conversation`
- `asset_package`
- `process_task`
- `user_preference`
- `sku_history`
- `analysis_insight`

规则：

- 写入时由当前登录用户自动填充。
- 查询时统一加 `owner_user_id = currentUserId` 过滤。
- 查不到就当作不存在。

---

## 五、Token 模型

### 5.1 access token：JWT

JWT 只承担短期访问态，建议 HS256 签名。

建议 claims：

| claim | 含义 |
|---|---|
| `sub` | userId |
| `uname` | username |
| `iat` | 签发时间 |
| `exp` | 过期时间 |
| `jti` | token 唯一 id |
| `typ` | `access` |

建议 TTL：

- access token：15 分钟
- 时钟偏差：30 秒

### 5.2 refresh token：Opaque Session Token

refresh token 不做 JWT，而是随机字符串：

- 长度足够长，至少 32 字节随机数。
- 只通过 HttpOnly Cookie 下发。
- 服务端只存 hash，不存明文。
- TTL 较长，建议 30 天。

### 5.3 黑名单

登出、改密、强制失效时，将当前 access token 的 `jti` 拉黑到 Redis，TTL 对齐 access token 剩余寿命。

---

## 六、注册 / 登录 / 刷新 / 登出流程

### 6.1 注册

`POST /api/auth/register`

流程：

1. 归一化 username。
2. 校验用户名和密码长度。
3. 查重。
4. BCrypt hash 密码。
5. 写入 `user_account`。
6. 签发 access token + refresh 会话。
7. 设置 refresh cookie。

注册成功后直接登录。

### 6.2 登录

`POST /api/auth/login`

流程：

1. 先读 Redis 登录失败计数。
2. 命中风控直接拒绝。
3. 查 `user_account`。
4. 校验密码 hash。
5. 写 `last_login_at`。
6. 签发 access token + refresh 会话。
7. 清理失败计数。

### 6.3 刷新

`POST /api/auth/refresh`

流程：

1. 从 HttpOnly cookie 取 refresh token。
2. 查 Redis 会话。
3. 校验 token hash。
4. 轮换 refresh token。
5. 签发新的 access token。
6. 返回新 access token，并更新 refresh cookie。

### 6.4 登出

`POST /api/auth/logout`

流程：

1. 删除 refresh 会话。
2. 若当前 access token 可解析，则把 `jti` 放入 access blacklist。
3. 清空 refresh cookie。

### 6.5 当前用户

`GET /api/auth/me`

返回当前登录用户的最小视图，用于前端恢复登录态和展示用户名。

---

## 七、Redis 键与 TTL

| Key | 用途 | TTL |
|---|---|---|
| `auth:refresh:{jti}` | refresh 会话 | 30d |
| `auth:blacklist:access:{jti}` | access token 黑名单 | access 剩余寿命 |
| `auth:fail:{username}` | 用户名失败计数 | 10m |
| `auth:fail-ip:{ip}` | IP 失败计数 | 10m |
| `auth:user:{userId}` | 用户快照缓存 | 5m |

原则：

- Redis 不存密码原文。
- refresh token 只存 hash。
- 所有 key 必须带 TTL。

---

## 八、鉴权过滤链与上下文

`JwtAuthenticationFilter` 在 Spring Security chain 中负责：

1. 提取 `Authorization: Bearer <token>`。
2. 验签与检查过期。
3. 查 access blacklist。
4. 读取用户快照或回源 DB。
5. 构造 `AuthPrincipal`，放入 `SecurityContextHolder` 和 `AuthContextHolder`。

默认放行：

- `/api/auth/**`
- `/actuator/health`
- 静态资源

其余接口都要求认证。

建议暴露两种读取方式：

- `AuthContextHolder.current()`：给 service 层用。
- `@CurrentUser`：给 controller 参数解析用。

---

## 九、前端与 WebSocket 接入

前端 HTTP 请求统一带：

`Authorization: Bearer <accessToken>`

access token 建议存内存，页面刷新后通过 refresh cookie 自动恢复。

STOMP `CONNECT` 也带同一个 access token。

若库或代理对标准头有限制，可兼容 `X-Auth-Token`，但语义仍然是 access token。

---

## 十、错误处理、限流与安全

建议为 auth 引入独立错误码，并在 `common` 里细化出 `AUTHENTICATION` 类别。

`AuthErrorCode` 建议包含：

- `AUTH_USERNAME_TAKEN`
- `AUTH_INVALID_CREDENTIALS`
- `AUTH_TOKEN_MISSING`
- `AUTH_TOKEN_INVALID`
- `AUTH_TOKEN_EXPIRED`
- `AUTH_TOKEN_REVOKED`
- `AUTH_REFRESH_EXPIRED`
- `AUTH_REFRESH_INVALID`
- `AUTH_ACCOUNT_DISABLED`
- `AUTH_TOO_MANY_ATTEMPTS`

限流采用 Redis 计数：

- 按 username 限流。
- 按 IP 限流。
- 命中后返回 429。

密码与 Cookie：

- 密码 hash 用 BCrypt。
- refresh cookie 设为 `HttpOnly + SameSite=Lax`。
- 本地开发可关闭 `Secure`，生产环境必须开启。

---

## 十一、配置项

```yaml
pixflow:
  auth:
    jwt:
      issuer: pixflow
      secret: ${PIXFLOW_AUTH_JWT_SECRET}
      access-ttl: 15m
      clock-skew: 30s
    refresh:
      ttl: 30d
      cookie-name: PIXFLOW_REFRESH
      cookie-path: /
      cookie-same-site: Lax
      cookie-secure: false
    password:
      bcrypt-strength: 12
    throttle:
      max-failures: 5
      window: 10m
      block-ttl: 10m
```

---

## 十二、对其他模块的契约

| 模块 | 契约 |
|---|---|
| `pixflow-app` | 暴露 `/api/auth/*` 与 STOMP 鉴权拦截器 |
| `common` | 提供 `ErrorCode` / `PixFlowException` / `Sanitizer` |
| `infra/cache` | 提供 Redis 会话、黑名单、限流计数 |
| `permission` | 只消费 `AuthPrincipal` |
| `module/conversation` | 会话必须带 `owner_user_id` 过滤 |
| `module/file` | 素材包必须带 `owner_user_id` 过滤 |
| `module/task` | 任务和结果查询必须按当前用户归属过滤 |
| `module/memory` | 用户偏好、分析结论必须按用户归属隔离 |
| `pixflow-web` | 统一携带 access token，refresh 走 cookie，401 后自动刷新 |

---

## 十三、测试策略

- 注册成功 / 用户名重复 / 密码弱校验。
- 登录成功 / 密码错误 / 登录失败限流。
- refresh 成功 / refresh 失效 / refresh 轮换。
- logout 后 access blacklist 生效。
- `me` 接口在 token 过期和账号禁用时返回 401 / 403。
- STOMP CONNECT 鉴权失败返回拒绝。
- owner 过滤测试：不同用户无法读到彼此的 conversation / package / task。
- Redis 容器集成测试：refresh 会话 TTL、黑名单 TTL、失败计数 TTL。

---

## 十四、暂不考虑

- SSO / OAuth2 / OIDC
- MFA / TOTP
- 邮箱 / 短信验证码
- 找回密码
- 设备管理页面
- 多租户 / 组织 / 细粒度 RBAC
- 登录审计后台

## Revision Notes

2026-07-01 / Codex: 新增 `infra/auth` 设计文档，采用“JWT access token + Redis refresh session + access blacklist”的单机实现口径，并把认证边界与 `permission` 的 HITL 授权边界分开。文档同时补了顶层资源 `owner_user_id` 归属、前端 / WebSocket 接入和登录失败限流，目标是把项目从“单用户无身份”细化为“单机可展示的完整账号体系”。
