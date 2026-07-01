# PixFlow 前端设计

> 面向 Vue 3 前端的架构与机制设计。范围：单用户单机的对话 / 任务 / 素材包 / 评分前端。
> 配套阅读：[`design.md`](design.md) 总架构、[`api.md`](api.md) 前端 API 契约、`module/file.md` 上传与解压落地、`module/conversation.md` 对话会话模型。
> 本文不写关键实现代码，只写**机制、状态机、协议、不变量与边界**。伪代码用于说明意图。
>
> **2026-07-01 起，本文同时承担"视觉与布局"权威**（与原 `refact-web.md` 合并）。任何对浅色主题、布局、组件库的修改以本文为准，不再单设 `refact-web.md`。

---

## 目录

- [一、文档定位与设计原则](#一文档定位与设计原则)
- [二、运行环境与目标](#二运行环境与目标)
- [三、技术栈选型](#三技术栈选型)
- [四、目录结构与分层](#四目录结构与分层)
- [五、视觉系统（浅色主题）](#五视觉系统浅色主题)
- [六、整体布局](#六整体布局)
- [七、组件清单（headless × 自绘视觉）](#七组件清单headless--自绘视觉)
- [八、中央主区（路由出口）](#八中央主区路由出口)
- [九、左侧栏：文件与历史](#九左侧栏文件与历史)
- [十、右侧栏：任务面板](#十右侧栏任务面板)
- [十一、Header 与鉴权](#十一header-与鉴权)
- [十二、输入框 @ 提及](#十二输入框--提及)
- [十三、图片网格（上传/结果通用）](#十三图片网格上传结果通用)
- [十四、传输层](#十四传输层)
- [十五、客户端协作流控](#十五客户端协作流控)
- [十六、分片上传（核心）](#十六分片上传核心)
- [十七、Agent 回合运行时](#十七agent-回合运行时)
- [十八、任务运行时](#十八任务运行时)
- [十九、状态管理（Pinia）](#十九状态管理pinia)
- [二十、跨模块联动](#二十跨模块联动)
- [二十一、HITL 流程的 UI 编排](#二十一hitl-流程的-ui-编排)
- [二十二、错误处理与 traceId](#二十二错误处理与-traceid)
- [二十三、决策记录（V1）](#二十三决策记录v1)
- [二十四、修订记录](#二十四修订记录)

---

## 一、文档定位与设计原则

`pixflow-web` 是 PixFlow 的唯一前端交付物。本文定义其**架构、状态机、协议、不变量、视觉系统、布局与组件库**，所有后续实现必须以此为准；与本文冲突的实现需先回溯修订本文。

模块专属设计原则：

1. **后端契约权威**。所有 HTTP / SSE / WS 端点、错误码、状态机均以 `api.md` 为准；前端不重新发明协议，不私自约定"前端字段"。
2. **客户端状态机显式**。Agent 回合、任务、分片上传都是有状态流程，**必须**抽出 composable / class 形式的有限状态机，不允许散落在组件的 `setTimeout` / `Promise.then` 里。
3. **断点续传与可恢复性是默认要求**。长耗时操作（分片上传、Agent 回合、任务进度）刷新、断网、杀进程后必须能恢复，不要求用户重头再来。
4. **失败自动重试，不打扰用户**。网络抖动、临时 5xx、令牌桶 429 全部由客户端 worker 自动退避重试；只在"重试耗尽"或"业务不可恢复错误"时打扰用户。
5. **去重与锁是后端职责**。前端不引入 Redisson 客户端；前端只通过"已传分片列表 / fileHash 命中"与"429 退避"配合后端实现幂等。
6. **限流是双向的**。前端先做令牌桶预限流（保护本地与对后端的请求节奏），后端再做最终令牌桶限流（防滥用）。前端令牌桶**不是**安全机制，是**协作流控**。
7. **不写关键代码**。本文记录机制、协议、状态机、不变量；具体 TS 实现放在 `pixflow-web/src/`，**不在设计文档里堆代码**。
8. **视觉与布局权威**。本文 §五 ~ §十三 是浅色主题、布局、组件库、交互的唯一权威。`refact-web.md` 已并入本文，原文件删除。
9. **图标只用 SVG**。本项目**禁止**在产品界面使用 emoji（包括 node_modules 里的 emoji 字体）。所有图标走 `lucide-vue-next`（线条、风格统一、tree-shake 友好）；文件树类型图标（包 / 图片 / 文件夹 / 会话）也走 SVG，自定义在 `src/components/icons/`。

---

## 二、运行环境与目标

- **单用户、单进程、单浏览器**。无登录强约束（V1 接 `pixflow-infra-auth` 轻量 JWT，单账号 / 单用户）、无 i18n（中文优先）、无移动端适配。
- **同机部署**：后端 Spring Boot + 前端 Vite dev server（或 Nginx 静态产物），浏览器与后端通过 `localhost` 通信。
- **可观测基线**：traceId 在 HTTP / SSE / WS 全链路透传；前端右下角常驻 traceId 浮窗。
- **韧性基线**：刷新 / 断网 / 杀进程后长耗时操作可恢复（具体见各运行时状态机）。
- **视觉基线**：**仅浅色主题**。不引入主题切换、不引入 `prefers-color-scheme` 暗色分支（决策：用户明确要求"不要深色主题"）。

---

## 三、技术栈选型

| 维度 | 选型 | 理由 |
|---|---|---|
| 框架 | Vue 3 + `<script setup>` + TypeScript 严格模式 | 与 `design.md` 一致 |
| 构建 | Vite 6 | 启动快、热更优 |
| 包管理 | pnpm | 单仓单包即可 |
| 路由 | vue-router 4 | **保留多页路由**（`/`、`/chat/:cid`、`/files`、`/tasks/:tid`），URL 可深链、可刷新恢复 |
| 状态 | Pinia | 多 store 按域切 |
| 样式 | **Tailwind CSS**（design token 化）+ **`radix-vue`**（headless，无障碍 + 行为）+ **自绘视觉层** | **替换原 Element Plus**；与参考图风格匹配 |
| 图标 | **`lucide-vue-next`** | 线条统一、tree-shake；**禁止在产品界面使用 emoji** |
| HTTP | 原生 `fetch` + 自封装 client | 不上 axios；统一注入 Idempotency-Key / traceId / 错误归一化 |
| SSE | `fetch` + `ReadableStream` 解析 | 绕过 `EventSource` 不能自定义 header 的限制 |
| WebSocket | `@stomp/stompjs` | 后端用 STOMP 协议 |
| 校验 | Zod | SSE 事件 / 表单 / API 响应 schema |
| 哈希 | Web Crypto `SubtleCrypto.digest('SHA-256', ...)` | 零依赖、性能好；**MD5 不被浏览器原生支持**，统一用 SHA-256 |
| 测试 | Vitest | 关键 runtime 状态机单测 |
| 鉴权 | `pixflow-infra-auth` 轻量 JWT（单账号/单用户） | 接入后端 `infra/auth.md` |

**否决项**（及理由）：

- ❌ **Element Plus**：视觉语言偏管理后台，不易做出"留白 + 柔和 + 大圆角"的现代感；改用 Tailwind + radix-vue + 自绘视觉层。
- ❌ **emoji 字符 / 表情符号字体**：产品界面统一 SVG 图标。技术文档（本文）允许用 emoji 描述交互。
- ❌ **Uppy / tus-js-client**：自写分片上传，状态机更可控，且要支持同 fileHash 整包去重。
- ❌ **axios / ofetch**：自封装 `fetch` 已经够薄。
- ❌ **TanStack Query**：单用户单机，手写缓存层即可，引入额外依赖收益不抵复杂度。
- ❌ **Pinia Plugin Persistedstate（持久化插件）**：上传会话需要更细粒度控制（key、TTL、清理），手写 `upload-session-store`。
- ❌ **Monorepo（pnpm workspace）**：单端交付物，单仓单包足够。
- ❌ **Storybook / PWA / Sentry / UnoCSS**：本期不引入。
- ❌ **深色主题 / 主题切换**：本期不实现。`tailwind.config.ts` 不开 `darkMode`，所有颜色走 `slate` / `stone` 暖灰系 + indigo 主色。

---

## 四、目录结构与分层

```
pixflow-web/
├── index.html
├── vite.config.ts
├── tailwind.config.ts          ← design token 化（§五）
├── postcss.config.cjs
├── tsconfig.json
└── src/
    ├── main.ts                 ← Pinia / Router / Tailwind 入口
    ├── App.vue
    ├── router/
    ├── api/                    # 薄：调 transport/ + utils/，不 import 组件
    │   ├── client.ts
    │   ├── auth.ts
    │   ├── conversations.ts
    │   ├── messages.ts
    │   ├── attachments.ts
    │   ├── confirm.ts
    │   ├── tasks.ts
    │   ├── packages.ts
    │   └── rubrics.ts
    ├── transport/              # 通用：SSE / WS，不依赖业务域
    │   ├── sse.ts
    │   └── ws.ts
    ├── upload/                 # 整文件状态机 + worker 池 + 退避
    │   ├── chunker.ts
    │   ├── hasher.ts
    │   ├── sessionStore.ts
    │   ├── uploadJob.ts
    │   ├── chunkWorker.ts
    │   └── cancel.ts
    ├── runtime/                # composable 状态机；不依赖 Pinia（除订阅）
    │   ├── useAgentTurn.ts
    │   ├── useTask.ts
    │   └── usePackageProgress.ts
    ├── stores/                 # Pinia；不依赖 api/（单向）
    │   ├── auth.ts
    │   ├── conversations.ts
    │   ├── agentTurns.ts
    │   ├── tasks.ts
    │   ├── packages.ts
    │   ├── uploadJobs.ts
    │   ├── fileIndex.ts         # 左栏文件树索引（@提及共用）
    │   ├── rubrics.ts
    │   └── ui.ts
    ├── pages/                  # 组合 runtime/ + stores/ + components/
    │   ├── HomePage.vue             # 路由 /
    │   ├── ChatPage.vue             # 路由 /chat/:cid
    │   ├── FilesPage.vue            # 路由 /files（含左栏选中）
    │   ├── TaskDetailPage.vue       # 路由 /tasks/:tid
    │   ├── LoginDialog.vue          # 不入路由，全局 Dialog
    │   ├── NotFoundPage.vue
    │   ├── PlaceholderView.vue      # /rubrics /settings 占位（Wave 6 范畴）
    │   └── RubricsPage.vue          # 占位
    │   └── SettingsPage.vue         # 占位
    ├── components/             # 视觉组件
    │   ├── layout/                  # 布局骨架
    │   │   ├── AppHeader.vue
    │   │   ├── AppShell.vue            # 三栏 shell
    │   │   ├── LeftPanel.vue
    │   │   ├── RightPanel.vue
    │   │   └── NetworkBanner.vue
    │   ├── ui/                      # 通用视觉组件（自绘）
    │   │   ├── AppButton.vue
    │   │   ├── AppInput.vue
    │   │   ├── AppTextarea.vue
    │   │   ├── AppCard.vue
    │   │   ├── AppBadge.vue
    │   │   ├── AppAvatar.vue
    │   │   ├── AppProgressBar.vue
    │   │   ├── AppSegmented.vue
    │   │   ├── AppDialog.vue          # 包装 radix-vue Dialog
    │   │   ├── AppDropdownMenu.vue    # 包装 radix-vue DropdownMenu
    │   │   ├── AppPopover.vue         # 包装 radix-vue Popover（@提及）
    │   │   ├── AppTooltip.vue         # 包装 radix-vue Tooltip
    │   │   ├── AppToast.vue           # 包装 radix-vue Toast
    │   │   ├── AppTabs.vue            # 包装 radix-vue Tabs（登录/注册）
    │   │   ├── AppAccordion.vue       # 包装 radix-vue Accordion（任务面板分组）
    │   │   ├── AppSelect.vue          # 包装 radix-vue Select
    │   │   ├── AppSwitch.vue          # 包装 radix-vue Switch
    │   │   ├── AppScrollArea.vue      # 包装 radix-vue ScrollArea
    │   │   ├── AppEmptyState.vue
    │   │   ├── AppSkeleton.vue
    │   │   └── TraceIdFloat.vue
    │   ├── home/                     # 首页专用
    │   │   ├── HeroUpload.vue
    │   │   └── LoginDialogContent.vue
    │   ├── chat/                     # 会话
    │   │   ├── MessageStream.vue
    │   │   ├── ChatBubble.vue
    │   │   ├── Composer.vue            # 含 @提及 + 上传触发
    │   │   ├── MentionPopover.vue
    │   │   ├── ProposalCard.vue
    │   │   ├── ChallengeDialog.vue
    │   │   └── FileTree.vue            # @提及下拉里复用
    │   ├── files/                    # 文件浏览器
    │   │   ├── FileTreePanel.vue       # 左栏文件树
    │   │   ├── FileTreeNode.vue
    │   │   ├── ImageGrid.vue
    │   │   ├── ImageGridToolbar.vue
    │   │   ├── ImageCard.vue
    │   │   ├── RenameDialog.vue
    │   │   └── ImagePreviewDialog.vue
    │   ├── tasks/                    # 任务面板
    │   │   ├── TaskPanel.vue
    │   │   ├── TaskCard.vue
    │   │   ├── TaskProgressCard.vue
    │   │   ├── TaskDetailHeader.vue
    │   │   └── ResultPreview.vue
    │   ├── upload/                   # 上传
    │   │   ├── UploadDropzone.vue
    │   │   ├── PackageUploader.vue
    │   │   ├── UploadJobCard.vue
    │   │   └── PackageExtractionProgress.vue
    │   └── icons/                    # 自绘 SVG 图标
    │       ├── IconPackage.vue
    │       ├── IconImage.vue
    │       ├── IconFolder.vue
    │       ├── IconChat.vue
    │       ├── IconUpload.vue
    │       ├── IconCheck.vue
    │       ├── IconX.vue
    │       └── ...（按需新增，全部 lucide 风格 24×24 stroke=1.75）
    ├── utils/                  # id / format / error / idempotency / devConsoleGuard
    └── types/                  # api / agent / upload / auth
```

**分层约束**：
- `api/` 只调 `transport/` 和 `utils/`，不直接 import 组件。
- `transport/` 不知道任何业务域。
- `runtime/` 是 composable，不依赖 Pinia（除非要订阅 store 状态）。
- `stores/` 不依赖 `api/`（单向数据流）。
- `pages/` 组合 `runtime/` + `stores/` + `components/`。
- `components/ui/*` 不依赖业务域；`components/{chat,files,tasks,upload}/*` 可订阅 store。
- `components/icons/*` 是**纯 SVG**（`<template><svg>...</svg></template>`），不引第三方图标包（除 `lucide-vue-next` 作可选底座）。

---

## 五、视觉系统（浅色主题）

### 5.1 设计原则

- **三层背景递进**：页面背景（page）→ 容器背景（sunken）→ 卡片背景（panel）。背景色全部走 §5.2 token，**禁止**硬编码 `#ffffff` / `#fafafa`。
- **暖灰基调**：所有中性色走 `stone`（Tailwind 内置），不混 `gray` / `zinc` / `neutral`，避免冷暖杂揉。
- **主色克制**：indigo 仅用于主操作（按钮、进度条填充、选中态边框）；次要操作走 `secondary` / `ghost`。
- **阴影轻**：阴影色统一带 `28,25,23` 暖灰基调 + 6%~8% 透明度，**禁止纯黑阴影**。
- **圆角统一**：四档 `sm 6 / md 10 / lg 14 / xl 20`，组件内不允许出现其它圆角。
- **边框有度**：最小 1px，颜色用 `border` token，**禁止 0.5px**。
- **图标统一**：所有产品界面图标走 SVG（lucide 风格），`components/icons/*` 自绘 24×24 stroke=1.75。

### 5.2 设计 token

Tailwind 配置（`tailwind.config.ts`）单源定义：

```ts
// tailwind.config.ts 节选
export default {
  darkMode: false,                              // 显式禁用
  content: ['./index.html', './src/**/*.{vue,ts}'],
  theme: {
    extend: {
      colors: {
        // 背景层
        bg: {
          page:   '#FAFAF9',  // stone-50
          sunken: '#F5F5F4',  // stone-100
          panel:  '#FFFFFF',
        },
        // 边框
        border: {
          DEFAULT: '#E7E5E4', // stone-200
          strong:  '#D6D3D1', // stone-300
        },
        // 文本
        fg: {
          primary:   '#1C1917', // stone-900
          secondary: '#57534E', // stone-600
          muted:     '#A8A29E', // stone-400
          inverse:   '#FFFFFF',
        },
        // 交互
        accent: {
          DEFAULT: '#4F46E5', // indigo-600
          hover:   '#6366F1', // indigo-500
          soft:    '#EEF2FF', // indigo-50（hover 背景）
        },
        // 状态
        success: { DEFAULT: '#10B981', soft: '#ECFDF5' },
        warning: { DEFAULT: '#F59E0B', soft: '#FFFBEB' },
        danger:  { DEFAULT: '#EF4444', soft: '#FEF2F2' },
        info:    { DEFAULT: '#3B82F6', soft: '#EFF6FF' },
      },
      borderRadius: {
        sm: '6px',
        md: '10px',
        lg: '14px',
        xl: '20px',
      },
      boxShadow: {
        sm: '0 1px 2px rgba(28,25,23,0.04), 0 1px 3px rgba(28,25,23,0.06)',
        md: '0 4px 12px rgba(28,25,23,0.06), 0 2px 4px rgba(28,25,23,0.04)',
        lg: '0 12px 32px rgba(28,25,23,0.08), 0 4px 8px rgba(28,25,23,0.04)',
      },
      fontFamily: {
        sans: [
          'system-ui', '-apple-system', 'Segoe UI', 'PingFang SC',
          'Microsoft YaHei', 'sans-serif',
        ],
      },
      fontSize: {
        xs: ['12px', { lineHeight: '1.5' }],
        sm: ['13px', { lineHeight: '1.5' }],
        base: ['14px', { lineHeight: '1.5' }],
        md: ['15px', { lineHeight: '1.5' }],
        lg: ['17px', { lineHeight: '1.4' }],
        xl: ['20px', { lineHeight: '1.35' }],
        '2xl': ['24px', { lineHeight: '1.3' }],
        '3xl': ['30px', { lineHeight: '1.25' }],
      },
      spacing: {
        '4.5': '18px',
        '5.5': '22px',
      },
    },
  },
} satisfies Config
```

**用法约束**：
- 颜色用 `bg-bg-page` / `bg-bg-panel` / `text-fg-primary` 等语义类，**禁止** `bg-stone-50` / `text-zinc-900` 等直接命中 Tailwind 调色板（防止后续调色板升级时全局跳变）。
- 阴影用 `shadow-sm` / `shadow-md` / `shadow-lg`，**禁止** `shadow-lg shadow-black/20`。
- 圆角用 `rounded-sm/md/lg/xl`，**禁止** `rounded-[8px]`。

### 5.3 字号与排版

| token | 用途 |
|---|---|
| `text-3xl` | 首页 hero 标题 |
| `text-2xl` | 页面标题（ChatPage / FilesPage / TaskDetailPage） |
| `text-xl` | 区块标题（任务面板分组、文件树段标题） |
| `text-lg` | 卡片标题（TaskCard / ImageCard / ChatBubble 标题） |
| `text-md` | 正文（消息、说明文字） |
| `text-base` | 表单输入、按钮 |
| `text-sm` | 辅助说明、面包屑 |
| `text-xs` | 徽章、状态标签、时间戳 |

### 5.4 视觉走查不变量

- ❌ 不允许出现深色背景面板（除"失败"高亮态用 `bg-danger-soft` 浅红）。
- ❌ 不允许出现纯黑阴影。
- ❌ 不允许出现 `0.5px` 边框。
- ❌ 不允许文字直接用 hex 值（必须走 token）。
- ❌ 不允许混用三种以上圆角档位。
- ❌ 不允许产品界面出现 emoji 字符（技术文档例外）。
- ❌ 不允许打开 `darkMode` / `prefers-color-scheme` 监听。
- ✅ 主色（accent）只用于主操作按钮、进度条填充、选中态边框。
- ✅ 卡片背景 `bg-panel` / 容器背景 `bg-sunken` / 页面背景 `bg-page` 三层递进。
- ✅ 左侧栏 / 右侧栏 / 中央三栏背景同 `bg-page`（视觉整合）。

---

## 六、整体布局

```
┌──────────────────────────────────────────────────────────────────────┐
│  AppHeader 56px      [logo]  [面包屑 / 页面标题]      [用户菜单]     │
├──────────────────┬───────────────────────────────┬─────────────────┤
│  LeftPanel       │  MainView   (路由出口)        │  RightPanel     │
│  280px           │                               │  360px          │
│  (默认展开)      │  /           HomePage         │  (默认收起)     │
│                  │  /chat/:cid  ChatPage         │                 │
│  [+ 上传]        │  /files      FilesPage        │  收起态: 右侧   │
│  ───             │  /tasks/:tid TaskDetailPage   │  8px 把手 +     │
│  上传            │                               │  3 枚徽章       │
│  └ IconPackage   │                               │                 │
│  结果            │                               │  展开态: 任务   │
│  └ IconChat      │                               │  面板（进行中 / │
│  ───             │                               │  已完成 / 失败）│
│  历史会话        │                               │                 │
│  └ ...           │                               │                 │
└──────────────────┴───────────────────────────────┴─────────────────┘
```

**布局不变量**：

1. **AppHeader 56px 固定**，logo + 面包屑 / 页面标题 + 用户菜单。
2. **LeftPanel 默认展开 280px**，可折叠为 8px 把手（鼠标 hover 自动展开浮层，移出 1.5s 收回；点"钉住"图标转常驻）。钉住态记入 `useUiStore.leftPanelPinned`，刷新后保留。
3. **RightPanel 默认收起**为右侧 8px 把手 + 3 枚垂直堆叠徽章（进行中 / 已完成 / 失败）；新任务产生时**自动展开 6 秒后收回**；用户可手动展开或点"钉住"转常驻。展开态切换记入 `useUiStore.rightPanelPinned`。
4. **MainView 占满剩余宽度**；路由切换即内容切换，不与左右面板互斥。
5. **左 / 右两侧背景同 `bg-page`**，与中央一致，避免三栏"白夹灰"割裂。
6. **窗口 < 1024px 时**仍按三栏渲染（不引入移动端响应式，V1 单机无此场景）。

---

## 七、组件清单（headless × 自绘视觉）

### 7.1 radix-vue（headless 层）

`radix-vue` 提供无障碍 + 行为，**不提供视觉**。所有视觉由 Tailwind class 覆盖在其子元素上。

| radix-vue 原语 | 包装组件 | 用途 |
|---|---|---|
| `Dialog` | `AppDialog` | 登录、HITL 二次确认、Challenge、预览大图、删除确认 |
| `DropdownMenu` | `AppDropdownMenu` | 用户菜单、批量操作、文件树节点操作 |
| `Popover` | `AppPopover` | @提及下拉 |
| `Tooltip` | `AppTooltip` | hover 提示（图标按钮、徽章） |
| `Tabs` | `AppTabs` | 登录/注册 Tab |
| `Toast` | `AppToast` | 错误条幅（替代 ElMessage） |
| `Accordion` | `AppAccordion` | 任务面板分组（进行中 / 已完成 / 失败） |
| `Select` | `AppSelect` | @提及列表（键盘可导航） |
| `Switch` | `AppSwitch` | 设置开关（Wave 6 范畴预留） |
| `ScrollArea` | `AppScrollArea` | 长列表滚动（任务面板、文件树） |
| `ToggleGroup` | `AppSegmented` | 视图切换（网格 / 列表） |

### 7.2 自绘视觉组件（Tailwind）

| 组件 | 视觉要求 |
|---|---|
| `AppButton` | 4 种 variant（primary / secondary / ghost / danger）× 3 种 size（sm / md / lg）；圆角 `md`；hover 微抬升 + 阴影从 `sm` 到 `md`；按下 `scale-[0.98]`；loading 态右侧 spinner。 |
| `AppInput` / `AppTextarea` | 边框 `border`、focus 边框 `accent` + ring `accent/20`；带前缀/后缀图标槽；`text-base`。 |
| `AppCard` | 白底 `bg-bg-panel` + 阴影 `sm`；hover 阴影 `md`；圆角 `lg`；可选 `border border-border`。 |
| `AppBadge` | 三种 tone（neutral / accent / success / danger / warning）；圆角 `full` 或 `sm`；`text-xs`；soft 态走 `*-soft` 背景。 |
| `AppAvatar` | 圆形 32px；`bg-bg-sunken`；首字 `text-fg-secondary`；支持图片（登录用户头像）。 |
| `AppProgressBar` | 圆角胶囊；轨道 `bg-bg-sunken`；填充 `accent`；高度 6px；平滑过渡 200ms。 |
| `AppSegmented` | 圆角 `md`；选中项 `bg-bg-panel shadow-sm`；未选中 `text-fg-secondary`。 |
| `AppEmptyState` | 居中；图标 48px `text-fg-muted`；标题 `text-lg`；副标题 `text-sm text-fg-secondary`；主操作按钮。 |
| `AppSkeleton` | `bg-bg-sunken` 圆角 `sm`；可选 pulse 动画。 |
| `TraceIdFloat` | 右下角固定浮窗；`bg-bg-panel shadow-md`；`text-xs`；点击复制到剪贴板。 |
| `NetworkBanner` | 顶部条幅；`bg-warning text-fg-inverse`；`text-sm`；带 spinner。 |

### 7.3 业务复合组件

| 组件 | 组成 | 视觉要求 |
|---|---|---|
| `AppHeader` | `AppButton` (icon) + `AppDropdownMenu` (用户菜单) | `bg-bg-panel` 底；底部 1px `border` 分隔；高度 56px |
| `AppShell` | 三栏容器 | 高度 `calc(100vh - 56px)`；横向 flex |
| `LeftPanel` | `FileTreePanel` + 历史会话 | `bg-bg-page`；右侧 1px `border`；可折叠 |
| `RightPanel` | `TaskPanel` | `bg-bg-page`；左侧 1px `border`；可折叠 |
| `FileTreePanel` | `FileTreeNode` 递归 | 缩进 12px / 级；选中态 `bg-accent-soft`；hover `bg-bg-sunken` |
| `FileTreeNode` | SVG 图标 + 文本 + `AppDropdownMenu` (操作) | 图标 16px `text-fg-secondary`；文本 `text-sm` |
| `ImageGrid` | 响应式 5/4/3/2 列 + `ImageGridToolbar` | 卡片间距 12px；选中态 `ring-2 ring-accent` |
| `ImageCard` | 缩略图 + 4:3 + hover 操作层 + checkbox | 圆角 `md`；hover 操作层 `absolute` 浮于底部 |
| `TaskCard` | 缩略图 + 标题 + `AppProgressBar` + 阶段标签 | `bg-bg-panel`；失败态 `bg-danger-soft` 描边 |
| `TaskPanel` | `AppAccordion` 三组 + 钉住/收起按钮 | 头部 `bg-bg-panel` + 底部 1px `border` |
| `MessageStream` | `ChatBubble` 列表 + 流式追加 | 自动滚到底；间距 12px |
| `ChatBubble` | 头像 + 文本 + 可选附件 | 用户气泡右对齐 `bg-accent-soft`；助手气泡左对齐 `bg-bg-panel` |
| `Composer` | `AppTextarea` + `AppPopover` (@提及) + 上传按钮 + 发送按钮 | 圆角 `lg`；`bg-bg-panel`；`shadow-sm` |
| `MentionPopover` | `AppPopover` 包裹 + `AppScrollArea` + 匹配项列表 | 项 hover `bg-bg-sunken`；选中 `bg-accent-soft`；图标 16px |
| `ProposalCard` | `AppCard` + 摘要 + 数据支撑 + 双按钮 | 选中边框 `border-accent` |
| `ChallengeDialog` | `AppDialog` + `AppInput` + 错误提示 | 标题 `text-xl`；错误 `text-danger` |
| `HeroUpload` | 大标题 + 副标题 + `UploadDropzone` | 垂直居中；标题 `text-3xl`；间距 40px |
| `UploadDropzone` | SVG 图标 + 提示 + `AppButton` | 虚线边框 `border-dashed`；dragover 态 `bg-accent-soft border-accent` |
| `LoginDialogContent` | `AppTabs` (登录/注册) + `AppInput` × N + `AppButton` | 宽度 400px；tabs 居中 |

### 7.4 图标清单（自绘 SVG，lucide 风格 24×24 stroke=1.75）

文件树类型图标 + 通用图标统一在 `src/components/icons/`。**禁止**任何产品界面出现 emoji。

| 组件 | 用途 |
|---|---|
| `IconPackage` | 压缩包节点 |
| `IconImage` | 单图节点 |
| `IconFolder` | 文件夹节点 |
| `IconChat` | 会话节点 |
| `IconUpload` | 上传按钮 / hero 图标 |
| `IconCheck` | 确认 / 成功 |
| `IconX` | 关闭 / 取消 |
| `IconChevronDown` / `IconChevronRight` | 文件树展开折叠 |
| `IconPin` / `IconPinOff` | 钉住 / 取消钉住 |
| `IconRefresh` | 重试 |
| `IconDownload` | 下载 |
| `IconTrash` | 删除 |
| `IconEdit` | 重命名 |
| `IconEye` | 预览 |
| `IconMoreHorizontal` | 更多操作（DropdownMenu 触发） |
| `IconExternalLink` | 在新窗口打开 |
| `IconAlertCircle` | 错误提示 |
| `IconLoader` | loading |
| `IconUser` | 用户菜单头像占位 |
| `IconCopy` | 复制 traceId |
| `IconSearch` | 搜索 |
| `IconPlus` | 添加 |
| `IconMinus` | 移除 |
| `IconSend` | 发送消息 |
| `IconPaperclip` | 附件 |

需要时引入 `lucide-vue-next` 作底座（仅当自绘成本过高时），但**禁止**把 emoji 或 emoji 字体路径写进任何 `.vue` / `.ts` 文件。

---

## 八、中央主区（路由出口）

`pages/` 下四个核心页面，组合 `runtime/` + `stores/` + `components/`。

| 路由 | 页面 | 关键组件 | 说明 |
|---|---|---|---|
| `/` | `HomePage` | `HeroUpload` | 首页 hero；未登录态点击上传触发 `LoginDialogContent` |
| `/chat/:cid` | `ChatPage` | `MessageStream` + `Composer` + `ProposalCard` + `ChallengeDialog` + 任务抽屉 | 会话区；URL `cid` 决定载入历史 |
| `/files` | `FilesPage` | `FileTreePanel` (左) + `ImageGrid` (中) | 文件浏览器；query `?folder=<id>` 决定当前文件夹 |
| `/tasks/:tid` | `TaskDetailPage` | `TaskDetailHeader` + `TaskProgressCard` + `ResultPreview` | 任务详情；URL `tid` 决定当前任务 |

**首页 `/` 特殊约定**：
- 未登录用户点击上传 → 弹 `<LoginDialogContent>`（Tabs：登录 / 注册，登录成功关闭弹窗，**不自动续传**）。
- 登录成功后保留在 `/`；用户手动再次点击上传即触发。
- 首次进入若 URL 是 `/chat/<不存在的 cid>` → 重定向到 `/`。

**URL 深链不变量**：
- 浏览器刷新 / 直接打开 `/chat/abc` → 必须能恢复会话流（先 `GET /api/conversations/{cid}/messages` 拉历史，再订阅 SSE）。
- 浏览器刷新 / 直接打开 `/tasks/123` → 必须能恢复任务详情（`GET /api/tasks/123` + WS 订阅 `/topic/task-progress/{cid}/123`）。
- 浏览器刷新 / 直接打开 `/files?folder=xyz` → 必须能恢复文件浏览器（`GET /api/files/packages/{pid}` 拉数据）。

---

## 九、左侧栏：文件与历史

```
├─ [+ 上传]                 ← AppButton primary sm；按下触发文件选择器，跳 /files?focus=upload
│
├─ 上传                      ← 段标题 text-xs uppercase text-fg-muted
│   ├─ IconPackage  春夏连衣裙.zip        ← FileTreeNode（缩略图 / 大小 / 上传进度）
│   ├─ IconImage    单图1.png
│   └─ ...
│
├─ 结果
│   ├─ IconChat     会话A
│   │   ├─ IconFolder   文件夹A1
│   │   └─ IconFolder   文件夹A2
│   └─ IconChat     会话B
│       └─ IconFolder   文件夹B1
│
├─ ── 分割线 ──
│
└─ 历史会话
    ├─ IconChat     把这批图都改成白底
    ├─ IconChat     给连衣裙添加水印
    └─ ...
```

**交互**：
- 节点类型图标统一用 `IconPackage` / `IconImage` / `IconFolder` / `IconChat`（§7.4），**禁止用 emoji**。
- 节点 hover 显示 `AppDropdownMenu`（重命名 / 删除 / 在新窗口打开（仅文件夹））。
- 选中节点 → 中央切到 `FilesPage` 并高亮该文件夹；左侧节点背景 `bg-accent-soft`。
- 折叠态：仅露 8px 把手（左边的暖灰细条 + logo 微缩），hover 浮出 280px 浮层（z-index 高于 MainView），移出 1.5s 收回；点把手上的 `IconPin` / `IconPinOff` 切换钉住态。
- "上传"段与"结果"段数据源：`useFileIndexStore`（Pinia），由 `usePackagesStore` + `useTasksStore` 派生（详见 §二十）。

---

## 十、右侧栏：任务面板

**触发规则**（沿用 `refact-web.md` §五 + auth 决策补充）：
- 新任务产生 → 自动展开 6 秒后收回（可关）。
- 用户点 `IconPin` → 钉住为常驻。
- 任务全部完成且无失败 → 提示"全部完成 ✓"后 4 秒自动收回。
- **未登录时不显示右侧栏**（避免在登录前推送任务状态）。

**展开后内容**：
```
├─ 任务面板 header         ← "任务 (N)"  + IconPin / IconX
├─ 进行中 (N)              ← AppAccordion item，折叠/展开由用户控制
│   └─ TaskCard × N         ← 缩略图 + 任务名 + AppProgressBar + 阶段标签 + 百分比
├─ 已完成 (N)
│   └─ TaskCard × N         ← 点击 → 跳 /files?folder=<对应文件夹>
├─ 失败 (N)
│   └─ TaskCard × N         ← 失败原因 + IconRefresh 重试按钮
```

**收起态**：右侧 8px 把手 + 三个垂直堆叠的徽章数字（进行中 / 已完成 / 失败），用 `AppBadge` 渲染。

**状态机对齐**（与 §十八 同步）：
- 进行中：`status ∈ {QUEUED, RUNNING}`
- 已完成：`status = COMPLETED`
- 失败：`status ∈ {FAILED, PARTIAL, CANCELLED}`

---

## 十一、Header 与鉴权

**Header 内容**（固定 56px）：
- 左侧：`IconPackage` logo + 文字" PixFlow"
- 中央：面包屑 / 页面标题（`text-lg`）
- 右侧：`AppAvatar` + `AppDropdownMenu`（账号信息 / 设置 / 退出）

**鉴权接入**（`pixflow-infra-auth` 轻量 JWT，单账号/单用户）：

- **触发登录弹窗的场景**（未登录时）：
  1. 首页 `/` 点击上传（点击 `UploadDropzone`）
  2. `Composer` 发送消息
  3. 左栏点击任何节点
  4. 右栏不显示徽章推送

- **存储**：
  - 优先：httpOnly cookie（由后端 `infra/auth.md` 控制）
  - 降级：`localStorage.pixflow.auth.token`（前端兜底；用于后端 cookie 不可用场景）

- **HTTP 注入**：`src/api/client.ts` 在请求头注入 `Authorization: Bearer <jwt>`；401 响应触发全局"会话过期" Toast + 跳 `/`。

- **路由守卫**：`src/router/index.ts` `beforeEach` 检查 `useAuthStore.isAuthenticated`；未登录且目标路由非 `/` 时跳 `/`，并在 query 记 `redirect=<原路径>`，登录成功后回跳。

- **用户菜单 DropdownMenu**：
  - 账号信息（占位，Wave 6 范畴补全）
  - 设置（跳 `/settings`，占位）
  - 退出登录（清 token + 跳 `/`）

---

## 十二、输入框 @ 提及

- **触发**：在 `Composer` 的 `AppTextarea` 中输入 `@` 字符 → 弹 `MentionPopover`。
- **数据源**：`useFileIndexStore.search(query)`，跨左栏"上传"段与"结果"段所有条目（包 / 图片 / 文件夹 / 会话）。
- **匹配**：按文件名 / 包名模糊匹配（前端本地，不发请求）；不区分大小写。
- **列表项**：图标（`IconPackage` / `IconImage` / `IconFolder` / `IconChat`）+ 文本 + 类型副标签（如"压缩包 · 12.4MB"）。
- **键盘交互**：↑↓ 选择 / Enter 确认 / Esc 取消。
- **确认后**：在 `AppTextarea` 内插入引用标签，用 `AppBadge` 渲染（带 `IconX` 删除按钮）。
- **提交时**：引用标签序列化为后端约定的引用语法（如 `@商品图_2024春季.zip`），详见 `api.md`（如未约定，与后端对齐时落地）。

---

## 十三、图片网格（上传/结果文件夹通用）

**顶部工具栏**（`ImageGridToolbar`）：
- 左侧：全选 / 取消全选（`AppButton` ghost sm）
- 中部：选中 ≥ 1 张时激活的批量操作（批量删除 / 批量下载 / 批量发起新任务）
- 右侧：`AppSegmented` 视图切换（网格 / 列表）+ 排序 + 过滤

**单卡**（`ImageCard`）：
- 缩略图 4:3，圆角 `md`，加载占位用 `AppSkeleton`
- hover：操作层浮于底部（`IconDownload` / `IconEdit` / `IconTrash` / `IconEye`）
- 选中：左上角 checkbox 显示 + 卡片 `ring-2 ring-accent`

**重命名规则**：
- 单张：内联 `AppInput` 直接编辑文件名
- 批量：弹 `RenameDialog` 统一前缀 + 自动序号 / 统一后缀 + 自动序号

**预览大图**（`ImagePreviewDialog`）：`AppDialog` 内 `<img :src="presignedUrl" />` + 元信息侧栏。

---

## 十四、传输层

### 14.1 HTTP 客户端

`api/client.ts` 统一处理：

- **traceId 注入**：每个请求 header 注入 `X-Trace-Id`（生成或透传），响应里回读（覆盖入参用于关联 Sentry breadcrumb）。
- **Idempotency-Key 注入**：`POST /confirm/.../submit` 与其他业务非幂等端点由调用方传 key，client 不自动生成（语义不可猜）。
- **Auth 注入**：从 `useAuthStore.token` 取 JWT，header 注入 `Authorization: Bearer <jwt>`。
- **错误归一化**：HTTP 4xx/5xx body 按 `base/common.md` 的 envelope 解析为 `ApiError { status, errorCode, message, traceId, details? }`，**不抛原生 `Error`**。
- **有限重试**：仅对 `GET` 在网络错 / 5xx 时自动重试 1 次（指数退避 500ms），其余方法由调用方决策。
- **不代理 SSE 字节**：SSE 通过专用 `transport/sse.ts` 走原始 `fetch` 流。

### 14.2 SSE 客户端

`transport/sse.ts` 行为：

- 用 `fetch` + `ReadableStream` 读 `text/event-stream`，**不**用浏览器原生 `EventSource`（后者不能自定义 header）。
- 解析 `event:` / `data:` / 空行分隔，注释行（`:` 开头）识别为 heartbeat，30s 无数据视为真断。
- 每个事件按 `api.md` 的事件名（`assistant_delta` / `tool_call_ready` / ...）派发到 handlers；事件 schema 用 Zod 校验，**校验失败记 trace breadcrumb 并触发 onError**。
- 断流语义：V1 **不**做 SSE 续传（`Last-Event-ID` 后端不支持），断流即视为回合失败，UI 提示用户重发。
- `AbortController` 透传：用户点"停止生成"立即中断读取。
- 浏览器 `visibilitychange`：切走 Tab 时不主动关闭 SSE（回合同步进行），切回时不 flush（流式渲染本就低帧率）。

### 14.3 STOMP 客户端

`transport/ws.ts` 行为：

- 用 `@stomp/stompjs` 连 `WS /ws/progress`，CONNECT 头预留 `X-Auth-Token`（V1 单机无用户系统，留空）。
- **重连退避**：1s → 2s → 4s → 8s → 30s（封顶），由 STOMP 客户端的 `reconnectDelay` 配合自定义退避表实现。
- **重订阅**：重连成功后**重放**之前订阅过的所有 destination（任务面板里所有在看的 taskId 重新订阅）。
- **服务端断 vs 客户端断**：STOMP `disconnect` 由前端主动调用，浏览器收不到 `close` 事件不视为错。
- **断线补偿**：重连成功后，对每个 taskId 调一次 `GET /api/tasks/{id}` 拉最新状态，与 WS 推送按 `(taskId, updatedAt)` 取较大者。
- **不持久化 WS 订阅**：刷新页面后从 Pinia `useTasksStore` 重建订阅集合。

---

## 十五、客户端协作流控

后端已落地完整令牌桶（见 `api.md` §`去重 / 锁 / 限流总览`），客户端**不再**实现完整令牌桶；只暴露一个轻量"全局并发保护"机制，仅作协作流控（防误用 / 退避示意），不做安全机制。

**核心约束**：
- 单用户单机场景下，`upload-chunk` 桶（cap=rps=并发数）恒为满，是零作用伪限流——已删除。
- 分片上传由 worker 池天然限流（默认并发数 = 4），与令牌桶叠加是过度设计。
- 客户端仅保留一个**全局 in-flight 信号量**：限制同时在飞的 HTTP 请求数（默认 6）。

**使用场景**：
- `api` 信号量：所有非上传 HTTP 请求受全局 in-flight 上限保护（`acquire()` → 请求 → `release()`），超限请求**入队等号**（不 sleep）。
- 上传分片 PUT：直接走 worker 池并发数限制（默认 4），不再过令牌桶。
- 后端返 `429 + Retry-After`：调用方按 `Retry-After` 退避，与客户端 in-flight 信号量独立。

**不变量**：
- 客户端协作流控**不**替代后端令牌桶；二者**叠加**：客户端保护本机节奏，后端兜底防滥用。
- 协作流控**不**是安全机制（用户可以改前端代码）。
- 客户端**不**对上传分片 PUT 单独做退避节流（worker 池就是节流）；后端 429 通过响应 `Retry-After` 头直接告知退避时长。

---

## 十六、分片上传（核心）

### 16.1 协议总览

| 阶段 | 前端 | 后端 | 备注 |
|---|---|---|---|
| 1. 算整包 hash | **切片累加 SHA-256**（4MB 块 → `subtle.digest` → hex 列表指纹 + 客户端传完整字节给服务端做二次校验） | — | Web Crypto 单次 digest 上限约 4GB，分块 4MB 内存峰值稳定 |
| 2. 调 init | 传 `{filename, size, fileHash, chunkSize}` | 同 fileHash 互斥锁 + 整包去重查 | 见 §16.2 |
| 3. 算分片 + 过滤 | `chunkFile(file, chunkSize)` 取全集；用 `uploadedChunks` 差集过滤 | — | 断点续传关键 |
| 4. 并发传分片 | `PUT /chunks/{index}`（带分片 hash） | 分片锁 + 幂等写 | 见 §16.8 |
| 5. 调 complete | 传整包 hash（可选但推荐） | 完整校验 + MinIO 拼接 + 落 `asset_package` | 见 §16.8 |
| 6. 订阅解压进度 | 订阅 `/topic/packages/{packageId}/progress` | `ProgressNotifier` 推帧 | 与上传解耦 |

**分片大小**：固定 5MB（`5242880` 字节）。前端按此切片，后端按此校验 chunk size 头。

**分片并发数**：默认 4。可配置（`pixflow.web.upload.concurrency`）。worker 池大小本身就是并发上限，无需令牌桶叠加。

### 16.2 整包去重

整包去重 = "同 fileHash 不重复解压"。

- 前端在 `init` 阶段把整包 hash 作为必传字段。
- 后端在 `init` 处理中：
  1. 取 `Redisson 锁 lock:package-upload:{fileHash}`（**看门狗续期**，TTL 与 `session.ttl` 对齐），检查 `asset_package.file_hash` 唯一索引：
     - **命中 READY/PARTIAL** → 返 `{mode: "DEDUP", packageId, status}`；前端**不再传任何分片**。
     - **未命中** → 创建 `package_upload_session`，返 `{mode: "UPLOAD", uploadId, ...}`。
  2. **同 fileHash 正在被另一客户端上传**（DB 中有 `status=UPLOADING` 的 session） → 返 `{mode: "DEDUP", status: "UPLOADING", uploadId: existingId, uploadedChunks: [...]}`，前端可接管续传或提示冲突。
- 前端对 `mode=DEDUP` 的处理：
  - `status=READY` → 提示"该压缩包已存在"，不进入上传流程，跳到对应 `packageId` 详情页。
  - `status=UPLOADING` → 提示"另一上传正在进行"，由用户选择"接管续传"或"放弃"。

**关键不变量**：
- **同 fileHash 互斥锁是后端责任**；前端**不**实现分布式锁。
- 前端在 `init` 返回 `DEDUP/READY` 时**不会**写任何 MinIO / Redis 字节。
- `asset_package.file_hash` 唯一索引是**最终防线**；即便锁失效，DB 唯一约束兜底。

### 16.3 续传定位（uploadId 持久化）

刷新 / 断网 / 杀进程后恢复 = "用 `fileHash` 找回 `uploadId`"。

**本地存储**（`upload/sessionStore.ts`）：
- key：`pixflow.upload.session.{fileHash}`
- value：`{ uploadId, filename, size, chunkSize, savedAt }`
- TTL：7 天（按 `savedAt` 过期；过期后调 init 会拿到新 `uploadId`）。
- 多标签页：通过 `localStorage` + `storage` 事件（V1 简化：不监听，依赖后端 `uploadedChunks` 列表自然去重）。

**恢复流程**（`uploadJob.run()` 入口）：
1. 读 `sessionStore.get(fileHash)`：若存在且未过期，把 `uploadId` 暂存为"恢复候选"。
2. 调 `POST /init`（不传 `uploadId`，让后端按 `fileHash` 决策）：
   - 返 `mode=UPLOAD` 且 `uploadId` 等于"恢复候选" → 走 `GET /sessions/{uploadId}` 拉 `uploadedChunks` 列表，差集上传。
   - 返 `mode=UPLOAD` 且 `uploadId` 不等于"恢复候选" → 视为"上一会话被后端回收（EXPIRED/CANCELLED）"，覆盖本地存储，走全新上传。
   - 返 `mode=DEDUP/READY` → 跳过上传。
3. 上传过程中持续更新 `sessionStore`（`uploadId` 持久化到分片 PUT 成功至少一次后）。
4. `complete` 成功后 `sessionStore.delete(fileHash)`（避免误命中陈旧会话）。

### 16.4 分片状态机

`uploadJob.ts` 的状态机（**整文件**粒度）：

```
idle
  → hashing            (算整包 hash)
  → initing            (调 /init)
  → uploading          (并发传分片)
       │   ↑↓
       │   retry (worker 内重试，详见 §16.6)
       ↓
  → completing         (调 /complete)
  → done
  → error              (重试耗尽 / 业务不可恢复)
  → cancelled          (用户主动取消)
```

分片粒度状态（在 `chunkWorker` 内部）：

```
PENDING → UPLOADING → UPLOADED
                  ↘
                   FAILED_RETRYING (退避中) → UPLOADING
                                          ↘
                                           FAILED (重试耗尽，整文件 → error)
```

**不变量**：
- 同一 `index` 同一时刻最多一个 worker 在写。
- `UPLOADED` 集合与后端 `uploadedChunks` 集合**在每个 PUT 成功后**用响应里的 `uploadedChunks` 字段校准（应对其他客户端的并发上传）。
- 整文件 `error` 时**保留**已上传分片（不主动清理），用户可重试"失败分片"或整文件；**用户点取消才 DELETE**。
- 整文件 `cancelled` 时 DELETE 会话 + 清本地 `sessionStore`。

### 16.5 并发与限流

**并发模型**：
- 固定大小 worker 池（默认 4）。worker 从共享队列取分片，空闲 worker 立即取下一个。
- **不**用 `Promise.all(chunks.map(upload))`：避免一次性占用所有网络连接。

**限流**：
- worker 池大小固定为并发上限（默认 4），无令牌桶。
- 后端返 `429 + Retry-After`：调用方按 `Retry-After` 退避后**重试**（不放回队列；保证顺序处理）。
- 退避上限：单分片最多重试 5 次（默认），单分片累计退避超过 60s 视为"长期拥塞" → 标记 `FAILED`，整文件 → `error`。

### 16.6 失败重试

**worker 内自动重试**（不暴露给用户）：
- 触发条件：网络错、5xx、429、`UPLOAD_SESSION_NOT_UPLOADING`（409，会话已被并发取消）。
- 退避：1s → 2s → 4s → 8s → 16s → 30s（封顶）；同一分片最多 5 次。
- **不**自动重试的：`CHUNK_HASH_MISMATCH`（4xx，客户端 bug，需用户重传整个文件）、`INCOMPLETE_CHUNKS`（complete 阶段才暴露，分片层面不会触发）。
- `ALREADY_EXISTS`（200，幂等命中）**不是错误**，记入 `uploadedChunks` 后跳过。

**整文件级失败**：
- 任一分片重试耗尽 → 整文件进入 `error` 状态，**不**自动重试整文件（避免死循环）。
- UI 显示"上传文件失败，请重试"按钮 → 用户点"重试" → 重置失败分片状态 → 重新入队（worker 自动重试机制复用）。

### 16.7 取消与清理

**用户主动取消**：
- 调 `AbortController.abort()` 中断所有在飞 PUT。
- 调 `DELETE /api/files/packages/sessions/{uploadId}` 通知后端清理 Redis 元数据 + MinIO 临时分片。
- 清本地 `sessionStore`。
- UI 立即进入 `cancelled` 态（不等待 DELETE 响应）。

**后端取消的传播**：
- 另一客户端 DELETE 同一 `uploadId` → 当前 worker 的下一次 PUT 返 `409 UPLOAD_SESSION_NOT_UPLOADING` → 整文件 → `cancelled`。
- 提示用户"该上传已被取消"。

**超时清理**（后端职责，前端不感知）：
- `package_upload_session` 设 TTL（默认 24h），过期后 `init` 拿到新 `uploadId`，本地 `sessionStore` 自动覆盖。

### 16.8 与后端去重/锁的协作边界

| 维度 | 前端责任 | 后端责任 |
|---|---|---|
| **整包 hash 计算** | 流式 SHA-256 | — |
| **整包去重查询** | — | `asset_package.file_hash` 唯一索引 |
| **同 fileHash 互斥** | — | `Redisson 锁 lock:package-upload:{fileHash}`（看门狗续期） |
| **分片切分** | 5MB 切片 | chunk size 校验 |
| **分片 hash 计算** | `crypto.subtle.digest('SHA-256', chunkBlob)` | 分片 hash 校验 |
| **分片去重** | 读 `uploadedChunks` 过滤 | `chunk:{uploadId}:{index}` Redis 元数据 + MinIO 单写语义 |
| **分片并发写保护** | 客户端并发控制（worker 池） | `Redisson 锁 lock:package-chunk:{uploadId}:{index}`（短时锁） |
| **complete 校验** | 传整包 hash | 分片总数校验 + MinIO `composeObject` 拼接 + hash 校验 |
| **complete 防并发** | — | `Redisson 锁 lock:package-complete:{uploadId}`（短时锁） |
| **cancel 防并发清理** | 调 DELETE | `Redisson 锁 lock:package-cancel:{uploadId}`（短时锁） |
| **速率限制** | 客户端令牌桶 | 服务端令牌桶（按 `uploadId` + IP 二级），429 + `Retry-After` |

**关键不变量**：
- 前端**不**引入 Redisson 客户端；**所有**分布式锁在后端。
- 前端**不**假设"上传一定成功"；每个 PUT 响应都用于校准 `uploadedChunks`（应对并发场景）。
- 前端**不**在本地缓存分片字节（不写 IndexedDB）；断网重传靠后端 `uploadedChunks` + 重新 `slice` 源文件。

---

## 十七、Agent 回合运行时

`runtime/useAgentTurn.ts`（composable）状态机：

```
idle
  → sending              (POST /messages)
  → streaming            (SSE connected, receiving deltas)
       ├─ tool_call_ready → tool_started → tool_result 循环
       ├─ challenge       (HITL 二次确认) → awaiting_challenge
       ├─ proposal + no challenge → awaiting_confirm
       └─ completed → idle
  → awaiting_challenge   (用户回答) → re-challenge → token → awaiting_confirm
  → awaiting_confirm     (用户确认) → submitting → taskId → idle
  → error                (SSE 断流 / 业务不可恢复)
  → cancelled            (用户点停止)
```

**关键不变量**：
- **流式 token 不进 Pinia**：`deltas` 维护在 composable 内的 `ref<string>`，直接喂 `<MessageStream>` 组件。Pinia 只存"回合摘要状态"（`phase`、`proposal`、`taskId`）。
- **confirmationToken 不出现在 console / 日志 / 任何调试输出**。
- **`Idempotency-Key` 在 `useAgentTurn.confirm()` 内生成并缓存**到 `sessionStorage`（key = proposalId，TTL 30min），刷新后能复用同一 key 避免重复扣款。
- **SSE 断流语义**：V1 不做续传，断流即 `error` 状态，提示用户重发同 prompt（前端可把 prompt 缓存到 localStorage 让用户一键重发）。
- **用户点拒绝** → 关闭 proposal，**不**调用 `/submit`；前端把"已拒绝"作为本地 USER 消息塞回合（让 LLM 知道用户拒绝），**不**调后端 `/messages`（避免污染远端 transcript）。
- **多回合并发**：同 conversationId 不允许两个 active 回合；新回合触发时若已有 active，先 abort 前一回合。

**与 SSE 客户端的衔接**：
- `useAgentTurn` 持有一个 `AbortController`；abort 时关闭 SSE 连接。
- 浏览器 `visibilitychange` 不中断 SSE（回合同步进行）。

---

## 十八、任务运行时

`runtime/useTask.ts`（composable）状态机：

```
queued → running → completed | failed | partial | cancelled
                    │
                    └─ (reconnect 触发) → 拉 GET 校准 → 回到 running/终态
```

**数据源**：
- **初始快照**：`GET /api/tasks/{id}`（拉 status + progress 一次性快照）。
- **实时更新**：WS 订阅 `/topic/task-progress/{cid}/{tid}`。
- **断线补偿**：WS 重连后**先**调 `GET` 拉一次最新，与本地 state 按 `updatedAt` 取较大者；**后**续接 WS 推送。

**取消语义**（对齐 `api.md`）：
- 用户调 `POST /conversations/{cid}/tasks/{tid}/cancel`。
- 前端**乐观更新**为 `cancelled`（按钮立即变灰）。
- WS 推送真 `cancelled` 信号时**幂等处理**（已在 `cancelled` 则不重复通知）。

**结果预览 / 下载**：
- `GET /api/tasks/{id}/results?page=` 拉结果列表。
- `GET /api/tasks/{id}/downloads?resultId=` 拿 presigned URL → **`window.open(url)`** 直接打开（**不代理字节**）。
- 单结果下载：`<img :src="presignedUrl" />`；批量下载：拼 `bundle` URL 后 `window.open`。

---

## 十九、状态管理（Pinia）

| Store | 关键 state | 关键 action |
|---|---|---|
| `useAuthStore` | `token`, `user`, `isAuthenticated` | `login(creds)`, `logout()`, `refresh()` |
| `useConversationsStore` | `items`、`currentId` | `refresh()`、`select(id)` |
| `useAgentTurnsStore` | `Map<conversationId, AgentTurnState>`、`lastTraceId` | `get(conversationId)`、`reset(conversationId)` |
| `useTasksStore` | `Map<taskId, TaskState>`、`watching: Set<taskId>` | `watch(taskId)`、`unwatch(taskId)` |
| `usePackagesStore` | `items`、`Map<packageId, PackageState>` | `refresh()`、`upsert(state)` |
| `useUploadJobsStore` | `Map<jobId, UploadJobState>` | `add(job)`、`update(jobId, patch)` |
| `useFileIndexStore` | `tree` (递归) | `search(query)`, `refresh()`, `upsertFolder()` |
| `useRubricsStore` | `templates`、`runs` | 占位（V1 不实现） |
| `useUIStore` | `leftPanelPinned`, `rightPanelPinned`, `floatingTraceId`, `networkOnline` | `toggleLeftPanel()`, `toggleRightPanel()`, `pinTaskPanel()` |

**反模式重申**：
- ❌ SSE 流式 `deltas` 进 Pinia → 失去响应式粒度，进 composable 的 `ref`。
- ❌ 后端列表数据塞 Pinia 重复缓存 → Pinia 只存**前端关心的状态副本**，原始数据从 fetch 即用。
- ❌ Pinia 持久化（整个 store）→ 仅 `upload/sessionStore` 手写精细持久化（key、TTL、清理策略可控）。

---

## 二十、跨模块联动

| 联动 | 触发 | 落地 |
|---|---|---|
| 上传成功 → 右任务面板新增卡片 | 上传状态机 `done` | `useTasksStore.upsert(task)` |
| 任务完成 → 左"结果"自动新增文件夹 | 任务 `process_task.status=COMPLETED` | `useFileIndexStore.upsertFolder()` + 收到 WS 帧后 `GET /api/files/packages/{pid}` 拉一次再更新 |
| 点右任务面板"已完成"卡片 → 跳左"结果"对应文件夹 | 用户点击 | 路由跳 `/files?folder=<id>` + 高亮该文件夹 |
| 输入框 @ 引用 → 读左侧"文件"数据源 | 输入 `@` | `useFileIndexStore.search(query)` |
| 用户上传时未登录 → 弹登录 | 触发上传 | 弹 `<LoginDialogContent>` |

**新增约束**（区别于 `refact-web.md` §八）：
- `taskId → packageId` 反查必须存在（任务与素材包强绑定），用于联动；后端 `process_task` 表已存 `packageId`，前端 `useTaskStore.getById(taskId)` 即可拿到。
- 联动不允许"乐观更新"（避免任务完成 → 文件夹出现的瞬间跳变）：等 WS 推送 `COMPLETED` 帧 + `GET /api/files/packages/{pid}` 拉一次后才更新左侧。
- 未登录态不显示右栏徽章推送（避免匿名访问的伪状态）。

---

## 二十一、HITL 流程的 UI 编排

`<ProposalCard>` 渲染**待确认提案**（Agent 不直接执行，前端是守门人）。

**用户点"确认执行" → 前端编排**（按 `api.md` HITL 章节）：

1. **调用 `/challenge`**：`POST /api/conversations/{cid}/confirm/{proposalId}/challenge`。
   - 返 `needChallenge=false` → 拿 token 直接进步骤 3。
   - 返 `needChallenge=true` → 弹 `<ChallengeDialog>` 阻塞。
2. **Challenge 回答**：用户输入答案 → 调 `/challenge` 带 `answer` 拿 token（**该接口设计上是同接口复用**：V1 简单做法；也可拆 `/answer` 子路径，**待后端确认**）。
3. **调用 `/submit`**：`POST /api/conversations/{cid}/confirm/{proposalId}/submit`，自动注入 `Idempotency-Key`。
   - 返 `taskId` → 跳到 `/chat/{cid}/tasks/{taskId}`。
   - 错误码 `PROPOSAL_CHALLENGE_FAILED` (400) → 焦点回到 challenge 输入框。
   - 错误码 `PROPOSAL_CHALLENGE_EXPIRED` (410) → 提示"二次确认已过期，请重新生成方案"。
   - 错误码 `PROPOSAL_ALREADY_CONFIRMED` (409) → 提示"已确认，跳转到任务面板"。

**用户点"拒绝"**：
- 关闭 proposal，**不**调 `/submit`。
- 前端把"已拒绝该方案"作为本地 USER 消息**插入回合 transcript**（不调后端 `/messages`），让 LLM 在下一回合看到拒绝结果。
- Pinia 中 proposal 状态置为 `REJECTED`。

**安全护栏**（前端必须做的）：
- "确认执行"按钮按下后立即 disable（避免双击）。
- `Idempotency-Key` 与 `proposalId` 绑定缓存到 `sessionStorage`（TTL 30min）；网络重试时**不**重新生成 key。
- Sentry 等日志**不**上报 `confirmationToken`，**可**上报 `proposalId` + `taskId` 关联（V1 暂未上 Sentry，留注释）。

---

## 二十二、错误处理与 traceId

**错误分类与 UI 表现**：

| 错误来源 | 示例错误码 | UI 表现 |
|---|---|---|
| 网络断 | `NETWORK_ERROR` | 顶部条幅 `NetworkBanner`："网络已断开，正在重连..." |
| SSE 断流 | `STREAM_INTERRUPTED` | 消息流下方"流中断，请重新发送" |
| WS 断线 | `WS_RECONNECTING` | 任务卡片小图标"连接中断（重连中...）" |
| 业务 4xx | `PROPOSAL_CHALLENGE_FAILED` 等 | 弹 `AppToast` warning/error，焦点回输入框 |
| 410 业务 | `PROPOSAL_CHALLENGE_EXPIRED` | 提示"已过期" |
| 5xx | `INTERNAL_*` | 通用错误 toast + traceId 复制按钮 |
| 上传 429 | `UPLOAD_RATE_LIMITED` | 上传卡片提示"被限流，自动重试中..." |
| 上传 400/409 | `CHUNK_HASH_MISMATCH` / `UPLOAD_SESSION_NOT_UPLOADING` | 上传卡片对应错误信息（不打断主流程） |

**traceId 浮窗**：
- `TraceIdFloat` 右下角常驻小窗显示当前会话的 traceId。
- 任何错误 toast 自带"复制 traceId"按钮。
- traceId 透传：HTTP 请求头 → 响应头回读 → SSE / WS 帧内携带。
- V1 暂未接 Sentry，traceId 仅用于**用户报障时人工检索**后端日志。

**关键不变量**：
- confirmationToken、用户上传文件字节、附件 presigned URL **不**出现在 console / 日志。
- proposalId / taskId / conversationId / traceId / packageId **可**出现在 console（仅调试模式，prod 关闭 console）。
- 产品界面**不**显示 emoji 字符；错误提示用文字 + SVG 图标（如 `IconAlertCircle`）。

---

## 二十三、决策记录（V1）

仅保留跨章节决策；与正文重复项（如"前端固定 5MB"、"客户端令牌桶默认值"、"Idempotency-Key 缓存介质"）由正文单一来源覆盖。

| 范围 | 决策 | 落地方式 |
|---|---|---|
| `package_upload_session` 存储 | **Redis only**（hash + set 结构） | 不引入新表；崩溃后依赖 init 重新创建 + 已传分片列表续传（详见 `api.md` §`上传会话存储与生命周期`） |
| `Idempotency-Key` 介质 | **sessionStorage**，key = `pixflow.idemp.{proposalId}`，TTL 30min；**多标签页共享同一 proposalId 时复用同一 key** | `useAgentTurn` 内置；提案幂等语义要求"同 proposalId 多次调 `/submit` 等同一次" |
| 二次确认 challenge 路径 | **复用 `/challenge` 接口**：第一次无 body 拿 challenge，第二次带 `{answer}` 拿 token | `api.md` HITL 章节同款；前端 `useAgentTurn.confirm()` 编排 |
| 客户端协作流控策略 | 删除 `upload-chunk` 桶（worker 池天然限流）；保留 `api` 全局 in-flight 信号量（默认 6），仅作协作流控 | `pixflow.web.api.inflight` 配置项；不再有完整令牌桶 |
| SHA-256 切片累加 | 分块 4MB → `subtle.digest` → hex 列表指纹 + 客户端传完整字节给服务端做二次校验 | 替代单次 digest；4MB 块在 100MB~1GB 文件下内存峰值稳定 |
| `confirmationToken` 脱敏层 | dev 模式 `console` wrapper（剥掉含 `confirmationToken` 字段的对象），V1 无 Sentry | `vite.config.ts` 配 `define: { __DEV__: ... }`；`main.ts` 装 wrapper |
| **视觉技术栈：弃 Element Plus** | **Tailwind CSS（token 化）+ radix-vue（headless）+ 自绘视觉层** | 本文 §三 / §五 落地 |
| **图标：禁止 emoji** | **统一 lucide 风格自绘 SVG（24×24 stroke=1.75）**；emoji 字符（含 emoji 字体）禁止进入产品界面 | `src/components/icons/*` 落地 |
| **浅色主题** | **仅浅色，不实现深色**；`tailwind.config.ts` `darkMode: false` | 本文 §五 落地 |
| **布局：左默认展开 / 右默认收起** | LeftPanel 默认 280px 展开 + 可钉住；RightPanel 默认收起为把手 + 徽章 | 本文 §六 / §九 / §十 落地 |
| **路由：保留 vue-router 多页** | `/`、`/chat/:cid`、`/files`、`/tasks/:tid` 独立路由 | 本文 §八 落地；保留 URL 深链 + 刷新恢复 |
| **鉴权：接入 infra/auth 轻量 JWT** | 单账号/单用户；httpOnly cookie 优先，`localStorage` 降级；路由守卫 + HTTP 注入 | 本文 §十一 / §14.1 落地 |
| **refact-web.md 并入本文** | 原 `docs/design-docs/refact-web.md` 删除；其内容并入本文 §五~§十三 + §二十 | 2026-07-01 |

---

## 二十四、修订记录

- **2026-07-01**：视觉与组件库重构完成，落地 `web-frontend-refactor-plan.md` R1~R7：`pixflow-web` 移除旧视觉库依赖，启用 Tailwind v3 + radix-vue + 自绘视觉层 + 自绘 SVG 图标；自动验证 `pnpm typecheck`、`pnpm test`、`pnpm build` 通过。
- **2026-07-01**：原 6 项 web.md 重整（令牌桶瘦身 / SHA-256 切片累加 / §14 删除 / §15 瘦身 / §4 目录调整 / 占位标注 / 修订记录段）保留。
- **2026-07-01**：新增视觉与布局权威章节 §五~§十三，吸收 `refact-web.md` 全部内容并升级为"浅色主题 / Tailwind + radix-vue + 自绘 / 路由保留 / 接 infra/auth"的统一方案。
- **2026-07-01**：原 `docs/design-docs/refact-web.md` 删除（决策记录见 §二十三）。
- **2026-07-01**：新增决策记录条目：弃 Element Plus、禁止 emoji、仅浅色主题、左默认展开/右默认收起、保留 vue-router 多页、接入 infra/auth JWT、refact-web.md 并入。
