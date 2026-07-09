# 实现前端注册界面与真实 JWT 登录链路

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续执行者必须在每个阶段更新本文档，使它始终能独立指导一个不了解历史上下文的人完成实现、验证和恢复。

## Purpose / Big Picture

当前用户从未登录状态进入 PixFlow 时，只能看到旧的登录 stub 文案，前端没有真正的注册表单；即使输入账号密码，也可能因为浏览器里残留的坏 JWT 被 HTTP client 自动带到 `/api/auth/login` 或 `/api/auth/register`，导致后端 JWT 过滤器先返回 401，控制器根本没有机会处理登录或注册。完成本计划后，未登录用户访问受保护页面或触发受保护动作时，会自动进入真实注册优先的鉴权界面，可以注册新账号并立即登录，也可以切换到登录页签使用已有账号登录；登录成功后会返回原先想去的页面或继续原先动作。

这个计划不是“最小修复”。它要把前端注册界面、登录界面、路由守卫、HTTP token 注入策略、错误提示、状态恢复和测试验收作为一个完整机制实现。可观察结果是：清空浏览器本地 token 与 refresh cookie 后访问 `/chat/new`，页面展示注册优先的鉴权界面；填写符合后端规则的用户名和密码后注册成功，前端保存 access token，后端设置 refresh cookie，并自动跳回 `/chat/new` 创建新会话。

## Progress

- [x] (2026-07-06 20:30 +08:00) 阅读 `PLANS.md`、`docs/design-docs/index.md`、当前执行计划目录，以及 `docs/design-docs/infra/auth.md`、`docs/design-docs/frontend/shell-routing-auth.md`、`docs/design-docs/frontend/transport-api.md`。
- [x] (2026-07-06 20:40 +08:00) 检查当前前端鉴权相关代码：`pixflow-web/src/api/client.ts`、`pixflow-web/src/api/auth.ts`、`pixflow-web/src/stores/auth.ts`、`pixflow-web/src/pages/LoginPage.vue`、`pixflow-web/src/router/index.ts`、`pixflow-web/src/pages/HomePage.vue`。
- [x] (2026-07-06 20:50 +08:00) 创建本执行计划，明确真实注册界面、登录链路、未登录自动进入注册态、JWT 注入豁免和验收方案。
- [x] (2026-07-06 16:05 +08:00) 执行 Milestone 1：修正鉴权请求通道，使登录、注册、刷新不携带残留 access token，并确认 refresh cookie 能随同源请求工作。
- [x] (2026-07-06 16:05 +08:00) 执行 Milestone 2：实现注册优先的鉴权界面，替换旧 `R6 stub` 登录页。
- [x] (2026-07-06 16:05 +08:00) 执行 Milestone 3：把路由守卫和首页受保护动作统一接入注册优先入口。
- [x] (2026-07-06 16:05 +08:00) 执行 Milestone 4：补充单元测试、组件测试和手工端到端验收记录。
- [x] (2026-07-06 16:05 +08:00) 执行 Milestone 5：确认后端 JWT 过滤器不会用坏 Authorization 阻断 `/api/auth/**`，并运行前后端验证命令。

## Surprises & Discoveries

- Observation: `pixflow-web/src/pages/LoginPage.vue` 仍然显示“当前为 R6 stub：任意非空账密均可登录”，但实际 `useAuthStore.login()` 已经调用 `/api/auth/login`。页面文案与真实行为冲突，会误导用户判断 401 的原因。
  Evidence: `LoginPage.vue` 的注释和模板仍包含 R6 stub 文案，而 `pixflow-web/src/api/auth.ts` 已经定义 `login()`、`register()`、`refresh()`、`me()`、`logout()`。

- Observation: `pixflow-web/src/api/client.ts` 当前会给所有请求自动注入 `Authorization: Bearer <token>`，包括 `/api/auth/login`、`/api/auth/register` 和 `/api/auth/refresh`。如果本地存了已过期、被撤销或格式错误的 token，登录和注册请求可能被 JWT 过滤器提前拒绝，形成“无法通过输入账号密码恢复”的死锁。
  Evidence: `request()` 无条件读取 `getAccessToken()` 并写入 `headers.Authorization`；`api/auth.ts` 调用 `request('/api/auth/login', ...)` 时没有任何跳过鉴权头的选项。

- Observation: 当前路由守卫访问受保护页面时只跳到 `/login?redirect=<原路径>`，没有表达“默认展示注册”这个产品意图。
  Evidence: `pixflow-web/src/router/index.ts` 的 `beforeEach` 在未认证时返回 `{ name: 'login', query: { redirect: to.fullPath } }`。

- Observation: 后端 Spring Security 已对 `/api/auth/**` 配置 `permitAll()`，但 `JwtAuthenticationFilter` 仍会先解析请求里的坏 `Authorization`，并在进入 controller 前写出认证错误。
  Evidence: `pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/filter/JwtAuthenticationFilter.java` 原实现无 `shouldNotFilter()`，`doFilterInternal()` 中只要提取到 token 就调用 `authService.authenticateAccessToken(token)`。

## Decision Log

- Decision: 把 `/login` 保留为独立鉴权页面，但让它成为“登录 / 注册双页签”的真实鉴权界面；路由守卫默认跳转到 `/login?mode=register&redirect=<原路径>`。
  Rationale: `docs/design-docs/frontend/shell-routing-auth.md` 已定义 `/login` 为 `meta.standalone` 的全屏页面，复用它最符合现有 shell 设计。用户说的“未登录时前端自动跳出注册界面”在本仓库中落地为“未登录自动展示注册优先的鉴权页”，而不是另起一个与路由状态脱节的浮层。
  Date/Author: 2026-07-06 / Codex

- Decision: 登录、注册、刷新请求必须显式跳过 `Authorization` 注入，`/api/auth/me` 和其他业务接口继续携带 access token。
  Rationale: 登录、注册、刷新是建立或恢复身份的入口，不能被旧身份污染。`/api/auth/me` 的语义是校验当前 access token，仍然需要鉴权头。
  Date/Author: 2026-07-06 / Codex

- Decision: 注册成功后直接视为登录成功，写入 access token 和 user，并按 `redirect` 返回原路径。
  Rationale: `docs/design-docs/infra/auth.md` 明确 `POST /api/auth/register` 的流程是创建用户后签发 access token 和 refresh 会话。前端不应要求用户注册后再登录一次。
  Date/Author: 2026-07-06 / Codex

- Decision: 前端只做与后端规则一致的即时校验，不重新定义账号体系。
  Rationale: 后端是安全边界。前端校验用于减少无效请求和给出清晰提示，必须与 `infra/auth.md` 保持一致：用户名归一化为小写，允许字母、数字、下划线，长度 3 到 32；密码长度 8 到 128；展示名可选。
  Date/Author: 2026-07-06 / Codex

## Outcomes & Retrospective

本计划已完成代码实现。前端 HTTP client 新增 `auth?: boolean`，默认维持业务请求携带 access token；`auth: false` 时不读取本地 token、不注入 `Authorization`，并且所有 fetch 请求显式使用 `credentials: 'same-origin'`。`login()`、`register()`、`refresh()` 均传入 `auth: false` 和 `noRetry: true`。

`LoginPage.vue` 已从旧 stub 替换为注册优先的真实鉴权页：默认注册，支持 `mode=login` 切换；注册字段包含用户名、展示名、密码、确认密码；前端按后端规则校验用户名和密码；错误码通过 `utils/authErrors.ts` 映射为中文并保留 traceId。路由守卫未认证访问受保护页面时跳转到 `/login?mode=register&redirect=<原路径>`；首页“新建对话”“我的素材”“上传素材”和 Composer 发送通过 `useAuthRedirect()` 统一进入注册优先入口。

后端 `JwtAuthenticationFilter` 已增加公开路径跳过逻辑，避免坏 `Authorization` 阻断 `/api/auth/login`、`/api/auth/register`、`/api/auth/refresh`。验证结果：`pnpm --dir pixflow-web typecheck` 通过；`pnpm --dir pixflow-web test` 通过，13 个测试文件、73 个用例；`mvn -pl pixflow-infra-auth -am test` 通过，infra-auth 模块 11 个用例通过，reactor build success。由于本次未启动完整前后端和浏览器，没有完成真实浏览器端到端注册验收；后续联调时仍需按本文 `Validation and Acceptance` 的浏览器步骤确认 refresh cookie 与真实账号数据。

## Context and Orientation

PixFlow 前端位于 `pixflow-web/`，使用 Vue 3、Pinia、Vue Router、Vite 和 Vitest。`pixflow-web/src/main.ts` 创建应用、安装 Pinia 与 router，并调用 `useAuthStore(pinia).restore()` 尝试恢复登录态。`restore()` 的意思是页面刷新后先用本地 access token 调 `/api/auth/me`，失败后再用后端 HttpOnly refresh cookie 调 `/api/auth/refresh` 获取新 access token。

JWT 是 JSON Web Token 的缩写。在本项目里，JWT access token 是浏览器发给后端 API 的短期登录凭证，前端通过 `Authorization: Bearer <accessToken>` 请求头发送它。refresh cookie 是后端设置的 HttpOnly Cookie，浏览器脚本不能读取它，但同源请求会自动携带它，后端用它给前端换新的 access token。

当前前端鉴权入口分布如下：

`pixflow-web/src/api/client.ts` 是所有普通 HTTP 请求的统一封装。它负责加 `X-Trace-Id`、解包后端 `{ success, data }` envelope、归一化错误、GET 自动重试、限制并发，并且当前会自动把 `getAccessToken()` 返回的 token 注入到 `Authorization` 请求头。

`pixflow-web/src/api/auth.ts` 是鉴权 API adapter。adapter 是薄封装，意思是它只负责路径、方法、请求体和 TypeScript 类型，不存业务状态。它已经有 `login()`、`register()`、`refresh()`、`me()`、`logout()`，路径分别对应 `/api/auth/login`、`/api/auth/register`、`/api/auth/refresh`、`/api/auth/me`、`/api/auth/logout`。

`pixflow-web/src/stores/auth.ts` 是 Pinia 鉴权状态仓库。它保存 `token`、`user`、`restoring`，并暴露 `isAuthenticated`、`login()`、`register()`、`restore()`、`logout()`、`clear()`、`getToken()`。`applyToken()` 会把后端返回的 access token 写入 `pixflow-web/src/transport/authToken.ts` 管理的存储，并把后端 user 视图映射成前端 `AuthUser`。

`pixflow-web/src/router/index.ts` 定义路由。`/login` 是独立全屏页，`/chat/:cid`、`/files`、`/tasks/:tid` 需要登录。全局 `beforeEach` 会在进入受保护路由前调用 `auth.restore()`，未认证时跳到 `/login?redirect=<原路径>`。

`pixflow-web/src/pages/LoginPage.vue` 是当前登录页，但它还是旧 stub 形态：只有账号和密码，没有注册表单，没有确认密码，没有展示名，没有登录 / 注册模式，也没有把后端 auth 错误码转成用户可理解的提示。

`pixflow-web/src/pages/HomePage.vue` 是首页。它本身不是受保护路由，但里面的“新建对话”“我的素材”“上传素材”和底部 Composer 发送最终都会进入需要登录的路径。为了让未登录用户更早看到注册界面，首页的这些动作也应该调用同一个跳转机制，而不是先进入业务页再被守卫拦截。

后端设计见 `docs/design-docs/infra/auth.md`。关键契约是：`POST /api/auth/register` 注册成功后直接签发 access token 和 refresh cookie；`POST /api/auth/login` 登录成功后签发 access token 和 refresh cookie；`POST /api/auth/refresh` 通过 refresh cookie 轮换会话并返回新 access token；`POST /api/auth/logout` 撤销 refresh 会话并清空 cookie；`GET /api/auth/me` 返回当前登录用户最小视图。设计还要求 `/api/auth/**` 默认放行，不应被缺失或错误 JWT 阻挡。

## Plan of Work

Milestone 1 先修通道，而不是先画表单。编辑 `pixflow-web/src/api/client.ts`，给 `RequestOptions` 增加一个明确选项，例如 `auth?: false` 或 `skipAuth?: boolean`。推荐命名为 `auth?: boolean`，默认 `true`，调用者传 `auth: false` 时不读取 `getAccessToken()`，不写 `Authorization`。同时给 `fetch` 增加 `credentials: 'same-origin'`，确保同源开发代理和生产同域部署下 refresh cookie 可以随请求发送。不要把这个选项命名成业务概念，如 `isLoginRequest`，因为未来也可能有其他公开接口需要跳过鉴权头。

同一里程碑编辑 `pixflow-web/src/api/auth.ts`。`login()`、`register()`、`refresh()` 都传 `auth: false` 和 `noRetry: true`。`logout()` 可以继续携带当前 access token，因为登出需要后端尽量把当前 access token 的 `jti` 加入黑名单；如果后端允许无 token logout，携带 token 也不冲突。`me()` 继续默认鉴权。完成后添加或更新 `pixflow-web/src/api/client.test.ts` 和 `pixflow-web/src/api/auth.test.ts`，用 mock `fetch` 和 mock `getAccessToken()` 证明登录、注册、刷新不会带 `Authorization`，业务请求会带，refresh 请求使用 same-origin credentials。

Milestone 2 实现真实鉴权界面。替换 `pixflow-web/src/pages/LoginPage.vue` 的旧 stub 内容。页面保留全屏独立体验，但内部提供两个模式：注册和登录。模式由 query 参数 `mode` 初始化，`mode=register` 默认选中注册，`mode=login` 默认选中登录；没有 `mode` 时默认注册，因为本计划要实现未登录自动进入注册态。界面不应再出现“任意非空账密”文案。

注册表单字段为 `username`、`displayName`、`password`、`confirmPassword`。登录表单字段为 `username`、`password`。提交注册前，前端将 `username` trim 后转小写，校验 3 到 32 位，只允许小写字母、数字、下划线；`password` 校验 8 到 128 位；`confirmPassword` 必须与 `password` 一致；`displayName` trim 后为空则不传。提交登录前，校验 username 和 password 非空，并将 username 同样 trim 后转小写。注册调用 `auth.register()`，登录调用 `auth.login()`。成功后显示成功 toast，并通过 `router.replace(redirect)` 返回 query 中的 `redirect`，没有 redirect 时返回 `/`。

错误展示要基于 `ApiError`。编辑 `LoginPage.vue` 或新建 `pixflow-web/src/utils/authErrors.ts`，将后端错误码映射成中文提示：`AUTH_USERNAME_TAKEN` 显示“用户名已被占用”，`AUTH_INVALID_CREDENTIALS` 显示“用户名或密码错误”，`AUTH_USERNAME_INVALID` 显示“用户名只能包含小写字母、数字和下划线，长度 3-32 位”，`AUTH_PASSWORD_INVALID` 显示“密码长度需为 8-128 位”，`AUTH_TOO_MANY_ATTEMPTS` 显示“尝试次数过多，请稍后再试”。未知错误显示后端 `message`，并保留 `traceId` 进入 toast 或页面错误细节，方便排障。

界面组件优先复用现有 UI：`AppCard`、`AppInput`、`AppButton`、toast store，以及项目已有的 design token 和 Tailwind 类。可以在 `LoginPage.vue` 内直接实现页签，也可以新增一个局部组件 `pixflow-web/src/components/auth/AuthPanel.vue` 承载表单逻辑。若表单超过约 180 行或测试需要直接挂载表单，推荐拆出 `AuthPanel.vue`，`LoginPage.vue` 只负责读取 query、传入初始模式和处理成功跳转。

Milestone 3 接入路由守卫和首页动作。编辑 `pixflow-web/src/router/index.ts`，在未认证访问 `meta.requiresAuth` 路由时返回 `{ name: 'login', query: { mode: 'register', redirect: to.fullPath } }`。登录页自身如果 `auth.restore()` 后已经认证，仍然跳回 `redirect` 或 `/`。如果用户显式访问 `/login?mode=login`，页面应展示登录模式；如果访问 `/login`，页面展示注册模式。

在 `pixflow-web/src/pages/HomePage.vue` 增加一个小函数，例如 `goProtected(path: string)`。它读取 `useAuthStore()` 的 `isAuthenticated`，未认证时跳到 `{ name: 'login', query: { mode: 'register', redirect: path } }`，已认证时直接 `router.push(path)`。把“新建对话”“我的素材”“上传素材”和底部 Composer 的发送都接到这个函数。这样用户在首页点“开始”或直接输入 prompt 后，会直接看到注册优先的鉴权页；注册完成后回到 `/chat/new` 或 `/chat/new?q=<prompt>`，继续原动作。

如果 `LeftPanel.vue`、`RightPanel.vue`、上传入口或其他组件也有进入 `/chat/new`、`/files`、`/tasks/:tid` 的按钮，执行者应搜索 `router.push('/chat`、`router.push('/files`、`to=\"/chat`、`to=\"/files`，将未登录时的入口也统一为同样的注册优先 redirect。不要在每个组件里复制大量逻辑；如果超过两个文件需要同样判断，新增 `pixflow-web/src/composables/useAuthRedirect.ts`，暴露 `requireAuthRedirect(target: string)`，内部使用 router 和 auth store。

Milestone 4 验证后端契约，必要时修复后端配置。先搜索后端 `AuthController`、`JwtAuthenticationFilter` 和安全配置，确认 `/api/auth/register`、`/api/auth/login`、`/api/auth/refresh` 允许匿名访问。若实际实现与 `docs/design-docs/infra/auth.md` 不一致，修复后端过滤器或 Spring Security permit 配置，使 `/api/auth/**` 在没有 Authorization 或 Authorization 错误时仍允许进入 auth controller；但不要让其他业务接口匿名放行。此里程碑还应确认后端 register 返回的数据结构与 `AuthTokenPayload` 一致：必须包含 `accessToken`、`accessTokenExpiresAt` 和 `user`，其中 `user` 至少含 `username`，最好含 `id` 或 `userId`。

Milestone 5 补齐测试和端到端验收。前端增加 Vitest 测试覆盖：HTTP client 跳过鉴权头；auth API 三个入口传 `auth: false`；auth store 注册成功后设置 token 和 user；登录页在 `mode=register` 时显示注册表单，在 `mode=login` 时显示登录表单；路由守卫未认证进入 `/chat/new` 时跳到 `/login?mode=register&redirect=/chat/new`。后端如有改动，运行 auth 模块测试。最后启动后端和前端，用真实浏览器完成“注册新用户、自动进入新会话、退出、再登录、刷新页面恢复登录态”的人工验收。

## Concrete Steps

从仓库根目录 `D:\study\PixFlow` 开始。先确认当前工作树，避免覆盖别人已经做的改动：

    git status --short

阅读并确认计划相关文档：

    Get-Content docs\design-docs\infra\auth.md
    Get-Content docs\design-docs\frontend\shell-routing-auth.md
    Get-Content docs\design-docs\frontend\transport-api.md

搜索现有鉴权代码和可能的入口跳转：

    rg "auth|Authorization|refresh|login|register" pixflow-web\src
    rg "router\.push|to=\"/chat|to=\"/files|/chat/new|/files" pixflow-web\src
    rg "AuthController|JwtAuthenticationFilter|api/auth|permitAll|SecurityFilterChain" pixflow-app pixflow-infra-auth

实现 Milestone 1 时，编辑 `pixflow-web/src/api/client.ts`。在 `RequestOptions` 中新增跳过鉴权头的选项，调整 token 注入条件，并给 fetch 加 credentials。预期核心行为是：

    const shouldAttachAuth = opts.auth !== false
    const token = shouldAttachAuth ? getAccessToken() : null
    if (token && !headers.Authorization) {
      headers.Authorization = `Bearer ${token}`
    }
    fetch(path, { method, headers, body, signal: opts.signal, credentials: 'same-origin' })

随后编辑 `pixflow-web/src/api/auth.ts`，让 `login()`、`register()`、`refresh()` 调用 `request()` 时传入 `auth: false`。

实现 Milestone 2 时，替换 `pixflow-web/src/pages/LoginPage.vue` 的 stub。若拆组件，新建 `pixflow-web/src/components/auth/AuthPanel.vue`，让它接收 `initialMode`，提交成功后 emit `authenticated`。`LoginPage.vue` 负责读取 `route.query.mode` 和 `route.query.redirect`，以及成功后的 `router.replace()`。

实现 Milestone 3 时，编辑 `pixflow-web/src/router/index.ts`，把未登录 redirect 改为带 `mode=register`。然后编辑 `pixflow-web/src/pages/HomePage.vue` 和搜索到的其他入口，使未登录用户先进入注册优先页。

实现 Milestone 4 时，若发现后端 auth endpoint 被 JWT 过滤器错误拦截，优先在安全配置中对 `/api/auth/**` 做匿名放行，或者在 `JwtAuthenticationFilter` 内对公开路径直接 `filterChain.doFilter(request, response)`。修复后运行后端 auth 测试。

实现 Milestone 5 时，在 `pixflow-web` 下运行：

    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test

如果后端有改动或需要确认 auth 模块：

    mvn -pl pixflow-infra-auth -am test
    mvn -pl pixflow-app -am test

启动本地应用进行人工验收。若使用后端 Spring Boot jar：

    java -jar pixflow-app\target\pixflow-app-1.0.0-SNAPSHOT.jar

另开终端启动前端：

    pnpm --dir pixflow-web dev

浏览器中清理 `localStorage['pixflow.auth.token']` 和 refresh cookie 后，访问 `http://localhost:5173/chat/new`。预期被重定向到 `/login?mode=register&redirect=/chat/new`，看到注册表单。注册新账号后，预期跳回 `/chat/new` 并创建会话。退出后访问 `/login?mode=login`，使用同一账号密码登录，预期回到首页或 redirect 页面。

## Validation and Acceptance

本计划完成的验收标准必须是用户可观察行为，而不只是代码编译。

第一，清空浏览器 token 和 cookie 后访问受保护路由 `/chat/new`、`/files`、`/tasks/some-id`。每个路由都应跳转到 `/login?mode=register&redirect=<原路径>`，页面默认显示注册表单。输入不合法用户名时，前端即时提示规则；输入两次不同密码时，前端提示确认密码不一致，不发送请求。

第二，在注册页输入新用户名、展示名和密码。前端发起 `POST /api/auth/register`，Network 面板中该请求不应带 `Authorization` 请求头，应带 `X-Trace-Id`，同源情况下应允许浏览器处理 refresh cookie。后端返回成功后，前端 store 中 `isAuthenticated` 变为 true，用户名称展示为 displayName 或 username，页面自动跳回 redirect。若注册重复用户名，页面显示“用户名已被占用”，不清空用户已经输入的其他字段。

第三，在登录页输入已有用户名和密码。前端发起 `POST /api/auth/login`，该请求不应带旧 `Authorization` 请求头。密码错误时显示“用户名或密码错误”；密码正确时写入 access token，跳回 redirect 或 `/`。如果先手工在 localStorage 放入一个坏 token，再登录，登录仍应成功或返回真实凭据错误，而不是被坏 token 导致的认证入口 401。

第四，刷新浏览器。`main.ts` 触发 `auth.restore()`，如果 access token 仍有效，`/api/auth/me` 成功并恢复用户；如果 access token 已失效但 refresh cookie 有效，`/api/auth/refresh` 成功并恢复用户。refresh 请求不应带坏 `Authorization` 请求头。

第五，运行自动化验证。`pnpm --dir pixflow-web typecheck` 应退出 0；`pnpm --dir pixflow-web test` 应退出 0，新增测试覆盖注册页默认模式、登录模式、auth API 跳过鉴权头、路由守卫 redirect。若修改后端，`mvn -pl pixflow-infra-auth -am test` 和相关 `pixflow-app` 测试也应退出 0。

第六，确认旧 stub 被完全移除。仓库内不应再出现“任意非空账密均可登录”或“R6 stub”这类会误导用户的登录文案：

    rg "任意非空|R6 stub|stub" pixflow-web\src

如果还有命中，必须逐个判断是否是历史文档或测试快照；生产页面中不得出现。

## Idempotence and Recovery

这些改动应是幂等的。重复运行 typecheck、test、dev server 不应改变代码或数据。重复访问 `/login?mode=register` 只应重置页面状态，不应自动创建账号。重复提交同一个用户名的注册请求，后端应返回 `AUTH_USERNAME_TAKEN` 或同义错误，前端显示清晰提示。

如果实现过程中发现本地已有大量未提交变更，执行者不得使用 `git reset --hard` 或 `git checkout --` 回滚。先用 `git status --short` 和 `git diff -- <file>` 判断哪些文件属于本计划，必要时把本计划的变更保持在少数文件中。若 `LoginPage.vue` 或 `router/index.ts` 已被他人修改，先阅读现有内容并在其基础上合并，不要覆盖。

如果前端测试因为缺少依赖失败，先运行 `pnpm --dir pixflow-web install`，再重试测试。如果后端测试因为 Redis 或数据库没有启动失败，记录失败原因，并至少完成前端单元测试与手工 mock 验证；不要把环境失败误判为代码通过。

如果后端 `/api/auth/register` 返回字段名与 `AuthTokenPayload` 不一致，不要在前端做脆弱的多套兼容，先核对 `docs/design-docs/infra/auth.md` 和后端 controller。设计文档要求返回 access token、过期时间和 user，因此后端应向设计对齐。只有在已有后端契约已经被其他模块使用且无法立即变更时，才在 `api/auth.ts` 中做局部 normalize，并在 `Decision Log` 记录原因。

## Artifacts and Notes

当前诊断要点如下，执行时应作为回归线索：

    pixflow-web/src/api/client.ts
    - request() 当前无条件读取 getAccessToken()
    - token 存在且 headers.Authorization 为空时会自动设置 Bearer token
    - fetch() 当前未显式设置 credentials

    pixflow-web/src/api/auth.ts
    - login/register/refresh 已存在，但没有跳过 Authorization 注入
    - me/logout 已存在

    pixflow-web/src/stores/auth.ts
    - login/register 成功后都会 applyToken()
    - restore() 先 me()，失败后 refresh()
    - isAuthenticated 依赖 token 和 user 同时存在

    pixflow-web/src/pages/LoginPage.vue
    - 当前只有登录表单
    - 当前仍显示 R6 stub 文案
    - 当前错误处理只用 e.message 或 String(e)，没有识别 ApiError.errorCode

    pixflow-web/src/router/index.ts
    - 受保护路由未认证时跳 /login?redirect=<原路径>
    - 计划要求改成 /login?mode=register&redirect=<原路径>

建议新增或更新的测试文件可以是：

    pixflow-web/src/api/client.test.ts
    pixflow-web/src/api/auth.test.ts
    pixflow-web/src/stores/auth.test.ts
    pixflow-web/src/pages/LoginPage.test.ts
    pixflow-web/src/router/index.test.ts

测试环境如果没有现成 Vue Test Utils，不要为了本计划引入大型测试库。可以优先用 Vitest 直接测试纯函数、API adapter、store 和 router guard；组件交互如必须细测，再评估是否引入 `@vue/test-utils`，并在 `Decision Log` 记录依赖增加的理由。

## Interfaces and Dependencies

`pixflow-web/src/api/client.ts` 在计划完成后应暴露如下兼容接口。原有调用不传 `auth` 时行为保持为“携带 token”；需要跳过鉴权头的调用显式传 `auth: false`。

    export interface RequestOptions {
      method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
      body?: BodyInit | unknown
      headers?: Record<string, string>
      idempotencyKey?: string
      traceId?: string
      multipart?: boolean
      signal?: AbortSignal
      noRetry?: boolean
      skipInFlight?: boolean
      timeoutMs?: number
      auth?: boolean
    }

`pixflow-web/src/api/auth.ts` 在计划完成后应保持这些函数名和返回类型，供 `stores/auth.ts` 调用：

    export function login(req: LoginRequest): Promise<AuthTokenPayload>
    export function register(req: RegisterRequest): Promise<AuthTokenPayload>
    export function refresh(): Promise<AuthTokenPayload>
    export function me(): Promise<AuthUser>
    export function logout(): Promise<void>

其中 `login()`、`register()`、`refresh()` 内部必须传 `auth: false`。`AuthTokenPayload` 必须包含：

    export interface AuthTokenPayload {
      accessToken: string
      accessTokenExpiresAt: string
      user: AuthUser
    }

如果新增 `pixflow-web/src/utils/authErrors.ts`，建议接口如下：

    import type { ApiError } from '@/types/api'

    export function authErrorMessage(error: unknown): string

这个函数接受任意异常，若它是 `ApiError`，优先根据 `errorCode` 映射中文；否则使用 `Error.message` 或通用“请求失败，请稍后重试”。页面不直接写一长串 errorCode 判断，避免注册和登录两处重复。

如果新增 `pixflow-web/src/composables/useAuthRedirect.ts`，建议接口如下：

    export function useAuthRedirect(): {
      goProtected: (target: string) => Promise<void>
    }

`goProtected()` 的行为是：已认证时 `router.push(target)`；未认证时 `router.push({ name: 'login', query: { mode: 'register', redirect: target } })`。它只能依赖 Vue Router 和 `useAuthStore()`，不能调用业务 API。

后端依赖按照 `docs/design-docs/infra/auth.md` 保持不变。`/api/auth/register`、`/api/auth/login`、`/api/auth/refresh` 必须允许匿名访问；其他业务 API 必须继续要求 JWT。不得为了让前端注册成功而把 `/api/**` 整体放开。

## Revision Notes

2026-07-06 / Codex: 新增本 ExecPlan。原因是用户明确要求不要做最小修复，而是按 `PLANS.md` 撰写中文计划，真正实现前端注册界面、未登录自动进入注册态，并使注册与登录按 JWT 设计文档端到端工作。本文记录了当前诊断、机制选择、实施里程碑、测试命令和验收标准。

2026-07-06 / Codex: 执行计划并更新结果。完成前端注册优先鉴权页、HTTP token 注入豁免、首页受保护入口、路由守卫 redirect、后端 JWT 过滤器公开路径跳过，以及前后端自动化测试。
