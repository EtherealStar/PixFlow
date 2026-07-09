# 前端 Shell、路由与鉴权设计

## 定位

Shell 模块负责 `pixflow-web` 的应用启动、全局路由、登录态拦截、三栏布局、网络状态条、traceId 浮层和全局 toast 容器。它不承载具体业务逻辑，只为 Chat、Files、Tasks 等页面提供稳定外壳。

## 关键实现

- `src/main.ts` 创建 Vue 应用，装配 Pinia 与 router，启动时调用 `useAuthStore().bootstrap()` 做一次认证恢复，dev 模式安装 `devConsoleGuard`。
- `src/router/index.ts` 定义多页路由：`/`、`/login`、`/chat/:cid`、`/files`、`/tasks/:tid`、`/rubrics`、`/settings` 和 404。
- `src/App.vue` 根据 `route.meta.standalone` 区分登录页全屏渲染与业务页三栏渲染。
- `src/components/layout/AppShell.vue`、`LeftPanel.vue`、`RightPanel.vue` 组成三栏布局。
- `src/stores/ui.ts` 管理左右面板钉住状态、右栏自动展开、网络在线状态和 traceId 浮层。
- `src/runtime/authSession.ts` 是前端认证状态机，统一处理启动恢复、登录、注册、登出、access token 过期刷新和竞态保护。

## 路由策略

| 路由 | 页面 | 说明 |
|---|---|---|
| `/` | `HomePage` | 首页与上传入口。 |
| `/login` | `LoginPage` | `standalone` 路由，不套三栏。 |
| `/chat/:cid` | `ChatPage` | 会话与 Agent 回合。`cid=new` 时页面创建会话后跳转。 |
| `/files` | `FilesPage` | 素材和产物图片浏览；通过 query 切换 tab 或聚焦结果。 |
| `/tasks/:tid` | `TaskDetailPage` | 任务详情、进度与结果。 |
| `/rubrics`、`/settings` | `PlaceholderView` | Wave 6 / V1 占位。 |

业务路由通过 `meta.requiresAuth` 声明鉴权要求。全局守卫只读取 `useAuthStore().isAuthenticated`，不在每次导航时主动请求 `/api/auth/refresh`。如果用户直接刷新受保护路由且启动恢复尚未开始，守卫会等待一次 `auth.bootstrap()`；如果恢复后仍未认证，则跳转 `/login?mode=register&redirect=<原路径>`。登录页使用 `meta.public`，并且登录页本身不触发 refresh，避免切换“注册 / 登录”页签时产生 401 噪音或覆盖刚写入的新 token。

## 认证状态机

认证状态机位于 `src/runtime/authSession.ts`。这里的“状态机”指一组明确的登录态阶段和只能由指定函数推进的状态变化，避免路由、页面和 HTTP client 分散修改 token。阶段包括 `anonymous`（未登录）、`bootstrapping`（启动恢复中）、`authenticated`（已登录）和 `expired`（曾登录但 refresh 失败）。`src/stores/auth.ts` 只代理这些响应式状态给页面读取，不再自己编排 refresh。

启动时，`src/main.ts` 调用一次 `bootstrap()`。`bootstrap()` 先用本地 `localStorage['pixflow.auth.token']` 中的 access token 调 `/api/auth/me` 校验；如果没有 token 或校验失败，再尝试 `/api/auth/refresh`。这两个请求只属于启动恢复，不应由登录页切换或普通路由跳转重复触发。登录和注册成功后，状态机直接写入新的 access token 与 user，并递增内部 generation。generation 是一个递增版本号，用来丢弃旧的异步恢复结果；如果旧 refresh 在登录成功后才返回 401，它不能清空新的登录态。

登出和显式清理会清掉 access token、user 与 STOMP 连接，并把当前认证状态视为已经确定的未登录状态。之后访问受保护路由会直接跳登录页，而不是再次尝试 refresh。

### Auth 真实字段对照（与后端 `UserView` / `AuthTokenPayload` 对齐）

| 前端 TS 字段 | 后端字段 | 状态 |
|---|---|---|
| `AuthUser.id?: string` | （不存在） | **删除**——后端 `UserView` 字段是 `userId: number`，没有 `id`。`authSession.ts#toUser` 已经在 fallback 到 `userId`/`username`，但 TS 类型应清理。 |
| `AuthUser.userId?: string \| number` | `userId: Long` | OK 但建议改为 `number` |
| `AuthUser.username: string` | `username: String` | OK |
| `AuthUser.displayName?: string \| null` | `displayName: String`（`UserView.from` 不允许 null） | OK |
| `AuthUser.status?: string` | `status: String` | OK |
| `AuthTokenPayload.accessToken` | `accessToken: String` | OK |
| `AuthTokenPayload.accessTokenExpiresAt: string` | `accessTokenExpiresAt: Instant` | OK（Instant 序列化为 ISO-8601 字符串） |
| `AuthTokenPayload.user` | `user: UserView` | OK |

### Refresh token 真实形态（关键事实）

- **refresh token 完全由后端管理**，通过 `Set-Cookie: PIXFLOW_REFRESH=<opaque>; HttpOnly; Secure=false(默认); SameSite=Lax(默认); Path=/; Max-Age=2592000(30d)` 下发；**前端不应也不能读**。
- `POST /api/auth/refresh` 调用**不带 body**，cookie 自动带上，由 `JwtAuthenticationFilter.shouldNotFilter` 放行 `/api/auth/**`，所以即使带了过期 access token 也不会被拦截。
- refresh token 不是 JWT，是 32 字节随机 base64 + UUID jti，后端**只存 SHA-256 hash** 在 Redis `auth:refresh:{jti}`。
- 登出时 `POST /api/auth/logout` 同时撤销 Redis session + 把 access token jti 写入 Redis blacklist `auth:blacklist:access:{jti}`，TTL = access 剩余寿命。

### 限流（来自 a14e1168 报告）

- `AUTH_TOO_MANY_ATTEMPTS` (429)：5 次失败 / 10 分钟，封禁 10 分钟；按 username 与 IP（`X-Forwarded-For` 优先）独立计数。登录页 toast 应区分 429（"稍后再试"）与 401（"用户名或密码错误"）。

## 布局状态

左栏默认钉住，右栏默认收起。`useUiStore` 将 `{ left, right }` 持久化到 `localStorage['pixflow.ui.panelPinned']`。右栏收到新任务时可调用 `autoExpandRightPanel(6000)` 临时展开；如果用户钉住右栏，则不自动收回。

网络状态由 `App.vue` 监听 `online/offline` 浏览器事件并展示 `NetworkBanner`。traceId 由 `useAgentTurnsStore.lastTraceId` 联动到 `useUiStore.floatingTraceId`，通过 `TraceIdFloat` 展示并支持复制。

## 约束

1. Shell 不直接调用业务 API；业务数据加载留给页面、store 或 runtime。
2. `App.vue` 只做全局框架装配，不放置页面特定分支。
3. 鉴权守卫只做前端可用性拦截，安全边界仍以后端 JWT 校验为准。
4. 新增需要登录的页面必须显式加 `meta.requiresAuth`。
5. 修改左右栏交互时必须保持 `useUiStore` 是唯一 UI 状态来源。
6. 登录页、路由 query 变化和页签切换不得触发 `/api/auth/refresh`；refresh 只允许出现在启动恢复或业务请求遇到 access token 过期后的单飞重试中。
7. **绝对不要**修改 `AuthUser.id` 的 fallback 逻辑——后端从不返回 `id`，所有用到 `state.user.id` 的下游代码应改为 `state.user.userId`。
