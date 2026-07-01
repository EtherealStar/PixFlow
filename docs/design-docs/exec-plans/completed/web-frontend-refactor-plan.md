# 完整重构 pixflow-web：Tailwind + radix-vue + 自绘视觉层 + 浅色主题

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`、`AGENTS.md`、`docs/design-docs/index.md`、`docs/design-docs/web.md`（视觉与布局权威，与本计划同步落地）、`docs/design-docs/api.md`（API 契约权威）、`docs/design-docs/design.md`（总架构）。本计划是"前端视觉与组件库重构"专项计划，专门落地 `web.md` §五~§十三 + §二十三 中已宣布但尚未在代码中实现的视觉决策。重构完成后，`pixflow-web` 不再依赖 Element Plus，技术栈统一为 **Vue 3 + Vite 5 + TypeScript + Tailwind v3 + radix-vue + 自绘视觉层 + 自绘 SVG 图标**，与 `web.md` 完全对齐。

> **范围声明**：本计划只做视觉层与组件库替换。运行时状态机（`useAgentTurn` / `useTask`）、传输层（SSE / WS / HTTP client）、分片上传状态机、Pinia stores、Auth 接入 等逻辑层代码**保留不变**，仅当其 UI 渲染依赖 Element Plus 时才替换为新视觉组件的接口绑定。

## Purpose / Big Picture

完成本计划后，`pixflow-web` 的视觉语言与 `web.md` §五~§十三 完全一致：

- 浅色主题：暖灰 `stone` 基底 + `indigo` 主色，三层背景递进（page / sunken / panel），柔和阴影 + 大圆角。
- 组件库：自绘视觉组件 + radix-vue headless 原语（Dialog / DropdownMenu / Popover / Tabs / Tooltip / Toast / Accordion / Select / Switch / ScrollArea / ToggleGroup）。
- 图标：自绘 SVG（lucide 风格 24×24 stroke=1.75），**禁止**在产品界面使用 emoji 字符。
- 布局：三栏（Header 56px + LeftPanel 280px 默认展开 + MainView + RightPanel 360px 默认收起），左 / 右可钉住 / 折叠。
- 路由：保留 vue-router 多页路由（`/`、`/chat/:cid`、`/files`、`/tasks/:tid`），URL 深链可刷新恢复。

用户能看到的效果：

- 浏览器访问 `http://localhost:5173`，看到浅色主题的 hero 上传区，间距 40px、`text-3xl` 标题，柔和阴影卡片。
- 左侧 280px 暖灰文件树：上传区段 + IconPackage 节点、结果区段（会话 → 文件夹）、历史会话区段；hover 出现 IconMoreHorizontal 操作菜单。
- 中央 /chat/:cid：浅色 `bg-bg-panel` 消息气泡（用户右对齐 `bg-accent-soft` / 助手左对齐 `bg-bg-panel`），底部 `Composer` 圆角 `lg` + `shadow-sm` + `@` 提及 `Popover`。
- 右侧任务面板：默认收起为 8px 把手 + 三枚垂直堆叠徽章；新任务出现时自动展开 6s 收回；可钉住转常驻。
- 任何按钮、输入框、对话框统一走 `AppButton` / `AppInput` / `AppDialog` 自绘组件，hover 微抬升 + 阴影从 `sm` 到 `md`、按下 `scale-[0.98]`。
- 全程不出现 Element Plus 的视觉特征（如蓝色主按钮 / 灰底折叠面板 / 默认蓝边框等），不出现 emoji 字符。

本计划**不**实现的功能（与本计划无关，继续在原 `web-frontend-implementation-plan.md` 体系内演进）：

- Rubrics / Settings 占位页面（Wave 6 范畴，V1 不实现）。
- 新的运行时状态机 / API 契约 / 鉴权流程。
- 后端任何模块的改动。

## 设计文档快速定位关键词

执行本计划时，所有视觉与布局决策以 `docs/design-docs/web.md` §五~§十三 为唯一权威。

| 主题 | web.md 章节 |
|---|---|
| 视觉原则、token 化、暖灰基调、阴影 / 圆角约束 | §五 |
| 整体布局（三栏 + Header） | §六 |
| 组件清单（radix-vue 包装 + 自绘视觉） | §七 |
| 中央主区（路由出口） | §八 |
| 左侧栏（文件与历史） | §九 |
| 右侧栏（任务面板） | §十 |
| Header 与鉴权 | §十一 |
| 输入框 @ 提及 | §十二 |
| 图片网格（上传/结果通用） | §十三 |
| 图标清单（25 个自绘 SVG） | §7.4 |
| 决策记录（弃 Element Plus / 禁 emoji / 浅色主题） | §二十三 |

## 进度追踪说明

本计划按"**基础设施 → 设计 token → 视觉原子 → 布局骨架 → 业务复合组件 → 业务页面接入 → 清理与验收**"七阶段推进，每阶段独立可验证。旧 Element Plus 代码以"逐目录替换"方式退役，每个目录替换完成即 `pnpm dev` + `pnpm vitest run` 双验证通过后才进入下一阶段。

## Progress

- [x] (2026-07-01) R1 基础设施完成：`element-plus` 依赖与入口引用清除，Tailwind v3 / PostCSS / radix-vue / lucide-vue-next 接入。
- [x] (2026-07-01) R2 图标系统完成：`src/components/icons/` 下自绘 SVG 图标落地，图标导出测试通过。
- [x] (2026-07-01) R3 视觉原子完成：`components/ui/*` 自绘组件与 radix-vue 包装组件落地。
- [x] (2026-07-01) R4 布局骨架完成：`AppHeader` / `AppShell` / `LeftPanel` / `RightPanel` 接入根应用。
- [x] (2026-07-01) R5 业务复合组件完成：chat / files / tasks / upload 组件迁移到新视觉层。
- [x] (2026-07-01) R6 业务页面接入完成：Home / Chat / Files / TaskDetail / Login / Placeholder / NotFound 页面接入新布局与 stores。
- [x] (2026-07-01) R7 清理与自动验证完成：残留扫描为空；`pnpm typecheck`、`pnpm test`、`pnpm build`、`pnpm vitest run --coverage` 均为 0 退出码；`pnpm dev -- --host 127.0.0.1 --port 5173` 前台启动保持运行直到工具超时，未观察到启动期崩溃。

---

## 准备工作（前置一次性动作）

- [x] 确认 `docs/design-docs/web.md` 已是 2026-07-01 重整后的版本（已确认）。
- [x] 在 `docs/design-docs/exec-plans/` 新建本计划 `web-frontend-refactor-plan.md`，R7 完成后归档到 `docs/design-docs/exec-plans/completed/`。
- [x] 标记旧计划 `web-frontend-implementation-plan.md` 的 M0~M8 为"视觉已过时，待本计划替换"，并在该文档 `Decision Log` 标注交接。

## 里程碑 R1：基础设施（删除 Element Plus + 引入 Tailwind / radix-vue / lucide）

R1 的目标：`pixflow-web` 依赖替换完成，`pnpm dev` 仍能启动（即便页面暂时白屏），TypeScript / Vitest 仍可运行。

- [ ] 编辑 `pixflow-web/package.json`：
  - 删除依赖 `element-plus` 及其类型。
  - 添加依赖：`tailwindcss@^3.4`、`postcss@^8.4`、`autoprefixer@^10.4`、`radix-vue@^1.9`、`lucide-vue-next@^0.460`（自绘底座补充）。
  - 添加 devDependencies：`@types/node`（已存在则跳过）。
  - `pnpm install` 重装依赖。
- [ ] 新建 `pixflow-web/tailwind.config.ts`：照搬 `web.md` §5.2 完整 token 定义（`darkMode: false`、colors、borderRadius、boxShadow、fontFamily、fontSize、spacing）。
- [ ] 新建 `pixflow-web/postcss.config.cjs`：
      module.exports = { plugins: { tailwindcss: {}, autoprefixer: {} } }
- [ ] 新建 `pixflow-web/src/styles/tokens.css`：在 `@tailwind base/components/utilities` 之前定义三层背景的 CSS 变量（`--bg-page` / `--bg-sunken` / `--bg-panel`），便于在 SVG 内部或第三方组件中复用。
- [ ] 编辑 `pixflow-web/src/main.ts`：删除 `import 'element-plus/dist/index.css'` 与 `app.use(ElementPlus)`；保留 Pinia / Router / 其他逻辑。
- [ ] 编辑 `pixflow-web/src/App.vue`：删除 `el-config-provider` 包裹（若有），保留 `<RouterView />` + `<TraceIdFloat />` + `<NetworkBanner />`。
- [ ] 删除 `pixflow-web/src/styles/element-plus-overrides.css`（若存在），删除与 Element Plus 相关的全局样式。
- [ ] 验收：`pnpm dev` 启动无报错，浏览器访问 `http://localhost:5173` 看到无样式的路由占位（不报错即可）；`pnpm vitest run` 通过；`pnpm typecheck` 通过。

## 里程碑 R2：图标系统（自绘 SVG + lucide 底座）

R2 的目标：25 个核心图标全部落地为 `src/components/icons/*.vue` 单文件组件，业务组件可直接 `import { IconPackage } from '@/components/icons'` 使用。

- [ ] 新建 `pixflow-web/src/components/icons/IconBase.vue`：统一外层 24×24 `<svg>` 容器（`stroke="currentColor" stroke-width="1.75" fill="none" stroke-linecap="round" stroke-linejoin="round"`），子组件只放 `<path>`。
- [ ] 新建 `pixflow-web/src/components/icons/index.ts`：统一 export 25 个图标。
- [ ] 按 `web.md` §7.4 实现以下 25 个图标（每个一个 `.vue` 文件，全部 24×24 stroke=1.75）：
  - 类型图标：`IconPackage` / `IconImage` / `IconFolder` / `IconChat`
  - 通用动作：`IconUpload` / `IconDownload` / `IconTrash` / `IconEdit` / `IconEye` / `IconRefresh` / `IconCheck` / `IconX` / `IconPlus` / `IconMinus` / `IconSearch` / `IconSend` / `IconPaperclip` / `IconCopy` / `IconMoreHorizontal` / `IconExternalLink` / `IconAlertCircle` / `IconLoader` / `IconUser`
  - 导航：`IconChevronDown` / `IconChevronRight` / `IconPin` / `IconPinOff`
- [ ] 在 `src/components/icons/lucide-fallback.ts` 提供 `lucide-vue-next` 的转出口，仅当自绘组件未覆盖且确实需要时由调用方使用（不强制 import）。
- [ ] 单测：在 `src/components/icons/__tests__/icon-snapshot.test.ts` 用 `vue-tsc` 编译检查 + happy-dom 渲染检查每个图标能正确产出 `<svg viewBox="0 0 24 24">` 根元素。
- [ ] 验收：`pnpm vitest run` 通过；`pnpm typecheck` 通过。

## 里程碑 R3：视觉原子（`components/ui/*` 自绘组件）

R3 的目标：20+ 视觉原子组件全部落地，业务组件可基于它们拼装。这一里程碑**不**修改任何业务组件，只产出"原子供给"。

### R3.1 表单类

- [ ] `AppButton.vue`：4 种 variant（`primary` / `secondary` / `ghost` / `danger`，`primary` 走 `bg-accent text-fg-inverse`，`secondary` 走 `bg-bg-panel text-fg-primary border border-border`）× 3 种 size（`sm` h-8 / `md` h-10 / `lg` h-12）；圆角 `rounded-md`；hover 阴影从 `shadow-sm` 升到 `shadow-md` + `translate-y-[-1px]`；按下 `active:scale-[0.98]`；`loading` 态右侧 spinner（用 `IconLoader` + `animate-spin`）；`disabled` 态 `opacity-50 cursor-not-allowed`。
- [ ] `AppInput.vue`：边框 `border border-border`、focus `border-accent ring-2 ring-accent/20`；圆角 `rounded-md`；`text-base`；支持 `prefix` / `suffix` 插槽放图标；支持 `error` 态（红边框 + `IconAlertCircle`）。
- [ ] `AppTextarea.vue`：基于 `AppInput` 视觉，扩展 `min-h` + auto-resize；`Composer` 用。
- [ ] `AppSwitch.vue`：包装 radix-vue `SwitchRoot`；轨道 36×20、拇指 16×16；轨道 `bg-bg-sunken`，checked 走 `bg-accent`；圆角 `full`。
- [ ] `AppSelect.vue`：包装 radix-vue `Select`，触发器走 `AppInput` 视觉（但用 `button` 元素），下拉走 `bg-bg-panel shadow-lg rounded-md`。

### R3.2 容器类

- [ ] `AppCard.vue`：白底 `bg-bg-panel` + `shadow-sm`，hover 阴影 `shadow-md`（可关）；圆角 `rounded-lg`；可选 `border border-border`。
- [ ] `AppBadge.vue`：5 种 tone（`neutral` / `accent` / `success` / `warning` / `danger`）；soft 态走 `*-soft` 背景 + `*-DEFAULT` 文字；实心态走 `*-DEFAULT` 背景 + `text-fg-inverse`；圆角 `rounded-full` 或 `rounded-sm`；`text-xs`。
- [ ] `AppAvatar.vue`：圆形 32px；`bg-bg-sunken`；首字 `text-fg-secondary`；支持图片（登录用户头像）。
- [ ] `AppProgressBar.vue`：圆角胶囊；轨道 `bg-bg-sunken`；填充 `bg-accent`；高度 6px；平滑过渡 200ms。
- [ ] `AppSegmented.vue`：包装 radix-vue `ToggleGroup`（`type="single"`）；圆角 `rounded-md`；选中项 `bg-bg-panel shadow-sm`；未选中 `text-fg-secondary`；容器 `bg-bg-sunken p-1`。
- [ ] `AppEmptyState.vue`：居中；图标 48px `text-fg-muted`；标题 `text-lg`；副标题 `text-sm text-fg-secondary`；可选主操作 `AppButton`。
- [ ] `AppSkeleton.vue`：`bg-bg-sunken rounded-sm`；可选 `animate-pulse`。

### R3.3 浮层类

- [ ] `AppDialog.vue`：包装 radix-vue `DialogRoot`；overlay `bg-fg-primary/40`；content `bg-bg-panel rounded-xl shadow-lg p-6 max-w-lg w-[90vw]`；标题 `text-xl`；支持 `description` / `footer` 插槽。
- [ ] `AppDropdownMenu.vue`：包装 radix-vue `DropdownMenuRoot`；trigger 透传；content `bg-bg-panel shadow-lg rounded-md p-1 min-w-[160px]`；item `px-3 py-2 text-sm rounded-sm hover:bg-bg-sunken focus:bg-accent-soft`。
- [ ] `AppPopover.vue`：包装 radix-vue `PopoverRoot`；content `bg-bg-panel shadow-lg rounded-md p-2`；用于 @提及下拉。
- [ ] `AppTooltip.vue`：包装 radix-vue `TooltipRoot`；content `bg-fg-primary text-fg-inverse rounded-sm px-2 py-1 text-xs`；delayDuration 200ms。
- [ ] `AppToast.vue`：包装 radix-vue `Toast`；4 种 variant（`info` / `success` / `warning` / `danger`），与 `AppBadge` 配色一致；右上角出现 + 自动消失 4s；可手动关闭。
- [ ] `AppTabs.vue`：包装 radix-vue `TabsRoot`；trigger list `border-b border-border`；active trigger `border-b-2 border-accent text-fg-primary`；inactive `text-fg-secondary`。
- [ ] `AppAccordion.vue`：包装 radix-vue `AccordionRoot`；item `border-b border-border`；trigger `py-3 px-2 flex items-center justify-between`；content `pb-3 px-2 text-fg-secondary`。
- [ ] `AppScrollArea.vue`：包装 radix-vue `ScrollAreaRoot`；滚动条 `bg-bg-sunken`，thumb `bg-border-strong rounded-full w-1.5`。
- [ ] `TraceIdFloat.vue`（**保留**）：右下角固定浮窗；`bg-bg-panel shadow-md rounded-md p-2`；`text-xs`；点击复制到剪贴板（用 `IconCopy`）。
- [ ] `NetworkBanner.vue`（**保留**）：顶部条幅；`bg-warning text-fg-inverse`；`text-sm`；带 `IconLoader animate-spin`。

- [ ] 单测（保留可迁移部分）：
  - 状态机相关（useAgentTurn / useTask / uploadJob）的 Vitest 用例**保留**，其依赖的 store 引用在重构后需要重新 import 路径（由后续里程碑改）。
  - 与旧 Element Plus 视觉绑定的快照测试（如 `el-button` 选择器匹配）**删除**。
  - 新增 `src/components/ui/__tests__/a11y.test.ts`：用 `@testing-library/vue` 验证 `AppDialog` / `AppDropdownMenu` / `AppTabs` 等 radix-vue 包装组件的关键无障碍属性（`role` / `aria-*`）存在。
- [ ] 验收：`pnpm vitest run` 通过；`pnpm typecheck` 通过；`pnpm dev` 启动无报错。

## 里程碑 R4：布局骨架（`components/layout/*`）

R4 的目标：三栏 shell + Header + 浮窗装配，**不**绑定业务数据（业务接入留 R6）。

- [ ] `AppHeader.vue`：`bg-bg-panel` + 底部 1px `border-b border-border`；高度 56px；左侧 `IconPackage` + " PixFlow" 文字（`text-lg font-semibold`）；中央面包屑 / 页面标题插槽；右侧 `AppAvatar` + `AppDropdownMenu`（账号信息 / 设置 / 退出 — 业务接入留 R6）。
- [ ] `AppShell.vue`：高度 `calc(100vh - 56px)`；横向 flex；三栏布局（左 / 中 / 右）。
- [ ] `LeftPanel.vue`：
  - 默认展开 280px；可折叠为 8px 把手。
  - 展开态：右 1px `border-r border-border`，背景 `bg-bg-page`。
  - 顶部 `AppButton` primary sm `[+ 上传]`。
  - 下方三段：上传 / 结果 / 历史会话，段标题 `text-xs uppercase tracking-wide text-fg-muted px-3 pt-4 pb-1`。
  - 折叠态：8px 把手 + `IconPin` / `IconPinOff` + 鼠标 hover 浮出 280px 浮层（z-index 高于 MainView），移出 1.5s 收回。
  - 钉住态读写 `useUIStore().leftPanelPinned`。
- [ ] `RightPanel.vue`：
  - 默认收起为 8px 把手 + 3 枚垂直堆叠徽章（`AppBadge` 渲染：进行中 / 已完成 / 失败 数量）。
  - 展开态：左 1px `border-l border-border`，背景 `bg-bg-page`，宽度 360px。
  - 展开时 header 包含"任务 (N)" + `IconPin` / `IconX`。
  - 内容由 `TaskPanel.vue`（R5）填充。
  - 钉住态读写 `useUIStore().rightPanelPinned`。
- [ ] `NetworkBanner.vue`（已存在则从 `components/ui/` 移到 `components/layout/`；R3 已实现）。
- [ ] `App.vue` 改造：用 `<AppShell>` 包裹 `<RouterView />`；顶部 `<AppHeader />`；右侧 `<TraceIdFloat />`；`<NetworkBanner>` 顶部条幅（通过 `useUIStore().networkOnline` 控制显隐）。
- [ ] 验收：`pnpm dev` 启动后浏览器看到三栏骨架（左 280px / 中 / 右 8px 把手），无报错。

## 里程碑 R5：业务复合组件（`components/{chat,files,tasks,upload}/*`）

R5 的目标：业务复合组件全部从 Element Plus 视觉迁移到自绘视觉层，**不**改业务逻辑（运行时 / store / transport 都不动）。

### R5.1 chat/*

- [ ] `MessageStream.vue`（迁移）：保留流式追加 + 自动滚到底；`flex flex-col gap-3`。
- [ ] `ChatBubble.vue`（迁移 / 新建）：
  - 用户气泡：右对齐 `bg-accent-soft rounded-lg px-4 py-3 max-w-[80%]`。
  - 助手气泡：左对齐 `bg-bg-panel shadow-sm rounded-lg px-4 py-3 max-w-[80%]`。
  - 头部：可选 `AppAvatar` + 角色名 + 时间戳。
  - 尾部：可渲染附件引用（`AppBadge` + `IconX`）。
- [ ] `Composer.vue`（迁移）：底部固定；`bg-bg-panel rounded-lg shadow-sm border border-border p-3`；内含 `AppTextarea` + `IconPaperclip` 上传按钮 + `IconSend` 发送按钮；`@` 触发 `MentionPopover`。
- [ ] `MentionPopover.vue`（迁移 / 新建）：包装 `AppPopover`；列表项 `IconPackage` / `IconImage` / `IconFolder` / `IconChat` + 文本 + 类型副标签；`hover:bg-bg-sunken`；选中 `bg-accent-soft`；↑↓ 键盘导航 + Enter / Esc 关闭。
- [ ] `ProposalCard.vue`（迁移 / 新建）：`AppCard` + 摘要 + 数据支撑 + 双按钮（"确认执行" primary + "拒绝" ghost）；选中边框 `border-accent`。
- [ ] `ChallengeDialog.vue`（迁移）：包装 `AppDialog`；内含 `AppInput` + 错误提示（`text-danger`）；提交按钮 + 取消按钮。
- [ ] `FileTree.vue`（在 R5 末尾新增，**R6 复用**）：`@提及下拉` 与左栏 `FileTreePanel` 共用的纯展示组件；`FileTreeNode` 递归；缩进 12px / 级；选中 `bg-accent-soft`；hover `bg-bg-sunken`；节点右侧 `IconMoreHorizontal` 触发 `AppDropdownMenu`（重命名 / 删除 / 在新窗口打开（仅文件夹））。

### R5.2 files/*

- [ ] `FileTreePanel.vue`（迁移 / 新建）：左栏文件树展示，复用 `FileTree.vue`。
- [ ] `FileTreeNode.vue`（迁移 / 新建）：单个树节点（图标 + 文本 + `AppDropdownMenu`）。
- [ ] `ImageGrid.vue`（迁移 / 新建）：响应式 5 / 4 / 3 / 2 列（`grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-3`）；卡片间距 12px；选中态卡片 `ring-2 ring-accent`。
- [ ] `ImageGridToolbar.vue`（迁移 / 新建）：
  - 左侧：全选 / 取消全选（`AppButton` ghost sm）。
  - 中部：选中 ≥ 1 张时激活的批量操作（批量删除 danger sm / 批量下载 secondary sm / 批量发起新任务 primary sm）。
  - 右侧：`AppSegmented`（网格 / 列表）+ 排序 + 过滤。
- [ ] `ImageCard.vue`（迁移 / 新建）：
  - 缩略图 4:3，圆角 `rounded-md`，加载占位 `AppSkeleton`。
  - hover：操作层 `absolute inset-x-0 bottom-0 bg-fg-primary/60 text-fg-inverse` 浮于底部，含 `IconDownload` / `IconEdit` / `IconTrash` / `IconEye`。
  - 选中：左上角 checkbox（用 radix-vue `Checkbox` 包装在 R3 末尾补一个 `AppCheckbox.vue`）显示 + 卡片 `ring-2 ring-accent`。
- [ ] `RenameDialog.vue`（迁移）：包装 `AppDialog`；表单：前缀 `AppInput` + 后缀 `AppInput` + 序号起始 `AppInput`（数字）。
- [ ] `ImagePreviewDialog.vue`（迁移）：包装 `AppDialog`；`max-w-4xl`；左侧 `<img>` + 右侧元信息侧栏。

### R5.3 tasks/*

- [ ] `TaskPanel.vue`（迁移 / 新建）：
  - 包装 `AppAccordion`，三组：进行中 / 已完成 / 失败。
  - 每组 header：标题（"进行中 (N)"）+ 右侧 `AppBadge` 数字。
  - item 内容：`<TaskCard />` 列表。
- [ ] `TaskCard.vue`（迁移 / 新建）：
  - 缩略图（首图）+ 任务名（`text-md font-medium`）+ `AppProgressBar` + 阶段标签（`AppBadge`）+ 百分比文字。
  - `bg-bg-panel rounded-lg shadow-sm`；失败态 `bg-danger-soft border border-danger`。
  - hover 阴影 `shadow-md`。
  - 点击：跳 `/files?folder=<对应文件夹>`。
- [ ] `TaskProgressCard.vue`（迁移）：在 `/tasks/:tid` 路由下用，包裹 `TaskCard` 视觉 + 取消按钮。
- [ ] `TaskDetailHeader.vue`（新建）：页面标题 + 返回按钮 + 任务元信息。
- [ ] `ResultPreview.vue`（迁移）：在 `/tasks/:tid` 路由下展示结果缩略图列表 + 批量下载按钮（`window.open(presignedUrl)`）。

### R5.4 upload/*

- [ ] `UploadDropzone.vue`（迁移 / 新建）：
  - 虚线边框 `border-2 border-dashed border-border-strong rounded-xl`；中央垂直布局：图标 48px `IconUpload text-fg-muted` + 提示文字 `text-md text-fg-secondary` + `AppButton` primary。
  - dragover 态：`bg-accent-soft border-accent`。
- [ ] `PackageUploader.vue`（迁移）：进度条 + 阶段标签 + 取消按钮。
- [ ] `UploadJobCard.vue`（迁移 / 新建）：左栏文件树"上传"段使用的卡片（缩略图 + 文件名 + `AppProgressBar` + 大小 + 速度 / 剩余时间）。
- [ ] `PackageExtractionProgress.vue`（迁移）：解压阶段独立进度条，订阅 `/topic/packages/{packageId}/progress` WS。

- [ ] 单测：
  - **保留**（迁移）：`useAgentTurn.test.ts` / `useTask.test.ts` / `uploadJob.test.ts` / `uploadChunkWorker.test.ts` / `sse.test.ts` / `ws.test.ts` / `client.test.ts`。
  - 重新 import 路径：`@/components/...` → 新路径（如 `@/components/ui/AppButton.vue` → `@/components/ui/AppButton.vue`，实际无变化但需确认别名解析）。
  - **删除**：所有依赖 `el-button` / `el-input` 等选择器的快照测试、组件级 unit test。
- [ ] 验收：`pnpm vitest run` 通过；`pnpm typecheck` 通过；`pnpm dev` 启动后浏览器路由切换看到业务组件（即便内部数据是 mock 占位）。

## 里程碑 R6：业务页面接入 + 鉴权 + 跨模块联动

R6 的目标：四大页面（Home / Chat / Files / Tasks）+ Login Dialog + Pinia 联动全部接入新视觉层。

- [ ] `pages/HomePage.vue`（重写）：`<HeroUpload>`（`text-3xl` 标题 + 副标题 + `UploadDropzone`）；垂直居中；间距 40px。
- [ ] `pages/ChatPage.vue`（迁移）：`<MessageStream>` + `<Composer>` + `<ProposalCard>` + `<ChallengeDialog>` + 任务抽屉（已由 `RightPanel` 接管）。
- [ ] `pages/FilesPage.vue`（迁移 / 新建）：左栏 `FileTreePanel`（已由 `LeftPanel` 接管）+ 中央 `ImageGrid` + `ImageGridToolbar`。
- [ ] `pages/TaskDetailPage.vue`（迁移 / 新建）：`<TaskDetailHeader>` + `<TaskProgressCard>` + `<ResultPreview>`。
- [ ] `pages/LoginDialogContent.vue`（迁移 / 新建）：
  - 包装 `AppDialog`；宽度 400px。
  - 顶部 `AppTabs`（登录 / 注册）。
  - 表单：`AppInput` × N（账号 / 密码）+ `AppButton` primary 提交。
  - 错误：`AppToast` + `IconAlertCircle`。
- [ ] `pages/NotFoundPage.vue`（新建）：`AppEmptyState` + 返回首页按钮。
- [ ] `pages/PlaceholderView.vue`（新建）：用于 `/rubrics` / `/settings` 占位（Wave 6 范畴）。
- [ ] `components/home/HeroUpload.vue`（新建）：首页专用，组合 `UploadDropzone` + 标题。
- [ ] 鉴权接入（**沿用** §十一 + `pixflow-infra-auth` 决策）：
  - `useAuthStore` 保留不变；HTTP 客户端在 `Authorization: Bearer <jwt>` 注入上保留。
  - 未登录态：点击 `[+ 上传]` / `Composer` 发送 / 左栏点击节点 → 弹 `<LoginDialogContent>`。
  - 401 响应：全局 toast + 跳 `/`。
- [ ] 跨模块联动（**沿用** §二十）：
  - 上传完成 → 任务面板新增卡片（订阅 `useTasksStore`）。
  - 任务完成 → 左"结果"自动新增文件夹（订阅 `useFileIndexStore` + 一次 `GET /api/files/packages/{pid}`）。
  - 点右栏"已完成" → 跳 `/files?folder=<id>`。
  - @ 引用 → 读 `useFileIndexStore.search(query)`。
- [ ] 验收：`pnpm dev` 启动后端 + 前端，全流程跑通：登录 → 上传 zip → 任务面板出现卡片 → 任务完成 → 点已完成跳文件浏览器 → @ 引用 + 发送消息 → 二次确认弹窗 → 取消按钮 → 任务面板消失。

## 里程碑 R7：清理 + 全量验证

R7 的目标：旧 Element Plus 痕迹全部清除；文档同步；`pnpm dev` / `pnpm vitest run` / `pnpm typecheck` / `pnpm build` 全部通过。

- [x] `grep -rE "element-plus|el-button|el-input|el-dialog|el-dropdown|el-popover|el-toast|el-tabs|el-accordion|el-select|el-switch|el-segmented|el-checkbox|el-message|ElMessage|el-notification" pixflow-web/src` 输出为空（无残留引用）。
- [x] `grep -rE "[\u{1F300}-\u{1FAFF}]|[\u{2600}-\u{27BF}]" pixflow-web/src` 输出为空（无 emoji 字符）。
- [x] `pixflow-web/src/styles/` 不再存在与旧视觉库相关的 CSS / SCSS 文件。
- [x] `pixflow-web/package.json` 的 dependencies 不再包含 `element-plus`。
- [x] `pixflow-web/tailwind.config.ts` 的 `darkMode: false` 显式标注（防止后续误开）。
- [x] `pnpm build` 产物可被 Nginx 静态托管，`index.html` 入口正确，`assets/*.js` 全部生成。
- [x] `pnpm vitest run` 通过；覆盖率已记录为本轮新基线（all-files statements 7.11%），低于原计划目标，后续需补 UI / runtime / store 测试。
- [x] `pnpm typecheck` 通过；无 TypeScript 错误。
- [x] 视觉走查（代码级，对照 `web.md` §5.4 + §七；浏览器级全流程走查留后续联调）：
  - 三栏背景均为 `bg-page`，无"白夹灰"割裂。
  - 阴影色为暖灰 `28,25,23` 透明，无纯黑阴影。
  - 圆角仅出现 `sm / md / lg / xl` 四档。
  - 所有图标走 SVG，**无 emoji**。
  - 任务面板默认收起，新任务产生时自动展开 6s 收回。
  - 左侧栏默认 280px 展开，可钉住。
  - 输入框 @ 触发 `MentionPopover`，键盘 ↑↓ + Enter 选中。
  - 二次确认 `ChallengeDialog` 阻塞主流程，错误态焦点回输入框。
- [x] 文档同步：
  - 在本计划 `Outcomes & Retrospective` 写最终结果。
  - 在 `docs/design-docs/exec-plans/completed/` 提交本计划（即本文档已位于该目录），并在 `docs/design-docs/index.md` 的"前端计划"索引中把本计划标记为"已实施"。
  - 在 `docs/design-docs/web.md` `修订记录` 追加：2026-XX-XX 视觉与组件库重构完成（落地本计划 R1~R7）。

## Concrete Steps（按里程碑分组的关键命令）

R1：

    cd d:\study\PixFlow\pixflow-web
    pnpm remove element-plus
    pnpm add -D tailwindcss@^3.4 postcss@^8.4 autoprefixer@^10.4
    pnpm add radix-vue@^1.9 lucide-vue-next@^0.460
    pnpm install
    pnpm dev

R3 验收：

    cd d:\study\PixFlow\pixflow-web
    pnpm vitest run
    pnpm typecheck

R5 / R6 验收：

    cd d:\study\PixFlow\pixflow-web
    pnpm dev
    # 浏览器访问 http://localhost:5173
    # 1. 登录（如果有种子账号）
    # 2. 上传一个 < 1MB 的 zip
    # 3. 看到任务面板出现卡片
    # 4. 等待任务完成
    # 5. 点已完成跳文件浏览器
    # 6. 在 ChatPage 输入 @ 触发 MentionPopover
    # 7. 发送消息触发 Agent 回合（流式）
    # 8. 二次确认弹窗（如果有 proposal） → 确认 / 拒绝

R7 验收：

    cd d:\study\PixFlow\pixflow-web
    pnpm vitest run
    pnpm typecheck
    pnpm build
    # 视觉走查（见 R7 第 9 条）

## Validation and Acceptance

| 维度 | 验收命令 / 操作 | 期望输出 |
|---|---|---|
| 依赖替换 | `pnpm list element-plus` | empty（无输出） |
| 依赖新增 | `pnpm list radix-vue tailwindcss lucide-vue-next` | 三者均存在 |
| TypeScript 严格性 | `pnpm typecheck` | 0 错误 |
| 单元测试 | `pnpm vitest run` | 全绿；状态机相关用例（useAgentTurn / useTask / uploadJob）全部通过 |
| 产物构建 | `pnpm build` | 0 错误；`dist/index.html` + `dist/assets/*` 生成 |
| 启动检查 | `pnpm dev` + 浏览器 | 浅色主题三栏布局；无控制台 error；路由切换正常 |
| 业务主流程 | 登录 → 上传 zip → 任务完成 → 文件浏览器 | 任务面板 / 左栏结果 / @ 引用全部按 `web.md` §二十 联动 |
| 视觉走查 | 对照 `web.md` §5.4 + §七 | 25 个不变量全部满足 |
| Element Plus 残留 | `grep -r "element-plus" pixflow-web/src` | empty |
| emoji 残留 | `grep -rP "[\x{1F300}-\x{1FAFF}]" pixflow-web/src` | empty |

## Idempotence and Recovery

- 本计划是**纯前端**改动，**不**涉及后端任何模块；后端 Maven 多模块无需重新构建。
- 任何里程碑都可独立回滚（`git checkout` 即可），因为每个里程碑对应一组独立 commit。
- 若 Tailwind 配置失误导致开发样式全乱：删除 `pixflow-web/tailwind.config.ts` + `pixflow-web/postcss.config.cjs` + `pixflow-web/src/styles/tokens.css` 即可回到无 Tailwind 状态。
- 若 radix-vue 与 Vue 3.5 不兼容：降级 radix-vue 到 `^1.8` 或暂时回退到 headless-ui-vue（需在 `Decision Log` 标注）。
- 旧 `web-frontend-implementation-plan.md` 的 M0~M8 在本计划完成后**保留**作为历史档案，不删除（决策点记录在 `Decision Log`）。

## Surprises & Discoveries

- Observation: R7 残留扫描不仅需要查业务源码，也需要查 `main.ts` 这类注释文本；旧视觉库名称和旧选择器名出现在注释中也会让验收命令失败。
  Evidence: `rg -n "element-plus|el-button|..." pixflow-web/src pixflow-web/package.json pixflow-web/pnpm-lock.yaml` 初次命中 `src/main.ts` 注释，清理后输出为空。
- Observation: Tailwind v3 对 `darkMode: false` 会给出升级提示，但本计划要求显式标注以防后续误开暗色分支，因此保留该配置。
  Evidence: `pnpm build` 0 退出码，并输出 Tailwind 关于 `darkMode: false` 的非阻塞 warning。
- Observation: pnpm v11 会执行 supply-chain policy 与 build-script 审批；重新解析 lockfile 后，过新的间接依赖被替换为满足策略的版本，但 `esbuild` / `vue-demi` 需要显式允许构建脚本。
  Evidence: `pnpm clean --lockfile` + `pnpm install` 后，新增 `pixflow-web/pnpm-workspace.yaml` 中 `allowBuilds` / `onlyBuiltDependencies`，最终 `pnpm install` 通过。
- Observation: 当前 coverage 通过但全仓覆盖率很低。原因是 coverage denominator 已包含大量新 UI / page 文件，而现有测试主要覆盖 utils、upload chunk/session/hash 和 icons。
  Evidence: `pnpm vitest run --coverage` 通过，All files statements 为 7.11%；后续应单独补 UI 组件与 runtime/store 测试。

## Decision Log

- Decision: 2026-07-01 — **彻底替换 Element Plus**（用户答复）。原 Element Plus 实现全部删除，按 `web.md` §七 完整实现 20+ 自绘视觉组件；radix-vue 仅作 headless 行为层。
  Rationale: 与 `web.md` §二十三 决策一致；维护成本更低；视觉语言更易统一。
- Decision: 2026-07-01 — **Tailwind v3 + PostCSS**（用户答复）。不使用 v4 的 Vite plugin，避免 v4 配置语法与 `web.md` §5.2 的 Config 对象不兼容。
  Rationale: 与设计文档的 `tailwind.config.ts` 直接对齐；生态最成熟。
- Decision: 2026-07-01 — **图标自绘优先，lucide-vue-next 作底座补充**（用户答复）。`src/components/icons/` 25 个核心图标全部自绘（24×24 stroke=1.75），额外图标按需从 lucide-vue-next 引入。
  Rationale: 与 `web.md` §7.4 一致；自绘成本可控；lucide 底座避免重复造轮子。
- Decision: 2026-07-01 — **单测有选择迁移**（用户答复）。运行时状态机 / 传输层 / 业务逻辑测试保留并迁移 import 路径；与 Element Plus 视觉绑定的快照测试删除；新增 radix-vue 包装组件的无障碍测试。
  Rationale: 视觉层与状态机解耦；状态机是无需改动的核心。
- Decision: 2026-07-01 — **本计划只做视觉与组件库替换，运行时 / 传输 / Pinia store 全部保留**。
  Rationale: `web.md` 视觉权威已发布，运行时逻辑已稳定；本计划聚焦于"让代码符合设计文档"，避免范围蔓延。
- Decision: 2026-07-01 — **旧 `web-frontend-implementation-plan.md` 保留作为历史档案**。
  Rationale: 旧计划的 M0~M8 描述了 Element Plus 时代的实现路径，对追溯代码历史有价值；其 M9~M14 视觉重构段被本计划取代。
- Decision: 2026-07-01 — **在 `pixflow-web/pnpm-workspace.yaml` 显式允许 `esbuild` 与 `vue-demi` 构建脚本**。
  Rationale: pnpm v11 默认阻断依赖 postinstall；这两个包是 Vite/Vue 工具链安装必需脚本，白名单比放宽全局策略更可控。

## Outcomes & Retrospective

R1~R7 已于 2026-07-01 完成，`pixflow-web` 代码层面已迁移到 Tailwind v3 + radix-vue + 自绘视觉组件 + 自绘 SVG 图标。旧视觉库依赖不再存在于 `package.json`，源码与 lockfile 的旧视觉库残留扫描为空，emoji 残留扫描为空。

最终落地的主要产物：

- `pixflow-web/tailwind.config.ts`、`postcss.config.cjs`、`src/styles/tokens.css`：视觉 token 与 Tailwind 入口。
- `src/components/icons/`：自绘 SVG 图标集合。
- `src/components/ui/`：AppButton / AppInput / AppDialog / AppDropdownMenu / AppTabs / AppAccordion / AppToastProvider 等自绘原子组件。
- `src/components/layout/`：Header + 三栏 shell + 左右面板。
- `src/components/{chat,files,tasks,upload}/` 与 `src/pages/`：业务复合组件和页面接入。
- `pixflow-web/pnpm-workspace.yaml`：pnpm build-script 白名单，仅允许 `esbuild` 与 `vue-demi`。

自动验证结果：

- `rg -n "element-plus|el-button|el-input|el-dialog|el-dropdown|el-popover|el-toast|el-tabs|el-accordion|el-select|el-switch|el-segmented|el-checkbox|el-message|ElMessage|el-notification" pixflow-web/src pixflow-web/package.json pixflow-web/pnpm-lock.yaml`：无输出。
- `rg -n -P "[\x{1F300}-\x{1FAFF}]|[\x{2600}-\x{27BF}]" pixflow-web/src`：无输出。
- `pnpm list element-plus`：无输出。
- `pnpm list radix-vue tailwindcss lucide-vue-next`：三者存在。
- `pnpm typecheck`：通过。
- `pnpm test`：8 个测试文件、66 个用例通过。
- `pnpm build`：通过，生成 `dist/index.html` 与 `dist/assets/*`。
- `pnpm vitest run --coverage`：通过；当前 all-files statements coverage 为 7.11%，不是质量目标，仅作为本轮基线记录。
- `pnpm dev -- --host 127.0.0.1 --port 5173`：前台进程保持运行直到工具超时，未观察到启动期崩溃；未在本轮完成浏览器级手工全流程联调。

与计划存在的偏差 / 后续补齐项：

- 本轮完成的是代码清理和自动验证；登录 → 上传 zip → 任务完成 → 文件浏览器 → @ 引用 → HITL 的端到端浏览器手工验收需要在后端联调环境稳定后单独执行。
- 覆盖率低于计划中“与原基线持平”的目标。需要补 `runtime/useAgentTurn`、`runtime/useTask`、transport、stores 以及关键 UI 包装组件测试，再重新定义前端覆盖率门槛。
- `pnpm build` 保留两个非阻塞 warning：Tailwind 对 `darkMode: false` 的提示，以及 `src/api/confirm.ts` 在 `useAgentTurn.ts` 中静态/动态重复 import 的 chunk 提示。前者按设计保留，后者可在后续小清理中消除。

## Context and Orientation

`pixflow-web` 是 PixFlow 唯一前端交付物，独立 npm 包，**不进 Maven 多模块**。当前（2026-07-01）实现基于 Vue 3.5 + Vite 5 + Pinia 2 + Element Plus，与 `web.md` §三 / §五 / §七 / §二十三 存在以下偏差：

1. 仍使用 Element Plus 作为视觉组件库（`web.md` 已要求弃用）。
2. 视觉系统未走 Tailwind + design token，依赖 Element Plus 的 CSS 变量。
3. 布局仍是 Element Plus 的 `el-container` / `el-aside`，未走 §六 的三栏 shell。
4. 没有 `components/ui/`, `components/layout/`, `components/icons/` 目录分层。
5. 图标依赖 Element Plus 内置图标或 emoji，**未**自绘 lucide 风格 SVG。

本计划**不**重命名或删除 `pixflow-web` 根目录；`pnpm dev` / `pnpm build` 命令保持不变。运行时（`src/runtime/`）、传输层（`src/transport/`）、上传（`src/upload/`）、API（`src/api/`）、Pinia stores（`src/stores/`）、工具（`src/utils/`）、类型（`src/types/`）七个目录**保留**原有代码，仅在必要时更新组件 import 路径与 props 接口。

## Plan of Work

按 R1 → R7 顺序执行。每个里程碑 R{i} 完成后做一次 `pnpm dev` + `pnpm vitest run` + `pnpm typecheck` 三件套验证，通过后才进入 R{i+1}。任何里程碑失败：先修复，不前进；超出 4 小时仍未修复则在 `Surprises & Discoveries` 记录并暂停。

具体的代码编辑不在本计划展开（按 `PLANS.md` 规则不在 ExecPlan 里堆关键代码），所有改动落地在 `pixflow-web/src/` 内。

## Artifacts and Notes

- `docs/design-docs/web.md` §五 / §七 / §二十三：本次重构的视觉与决策权威。
- `docs/design-docs/exec-plans/completed/web-frontend-implementation-plan.md`：旧计划，本计划取代其 M9~M14。
- `pixflow-web/tailwind.config.ts`：`web.md` §5.2 token 的代码落地点（R1）。
- `pixflow-web/src/components/icons/*`：25 个自绘 SVG 图标（R2）。
- `pixflow-web/src/components/ui/*`：20+ 自绘视觉原子（R3）。
- `pixflow-web/src/components/layout/*`：三栏 shell + Header（R4）。
- `pixflow-web/src/components/{chat,files,tasks,upload}/*`：业务复合组件（R5）。
- `pixflow-web/src/pages/*`：四大页面 + Login Dialog + Placeholder（R6）。

## Interfaces and Dependencies

新增依赖（落地在 `pixflow-web/package.json`）：

    dependencies:
      radix-vue: ^1.9          // headless 行为原语
      lucide-vue-next: ^0.460  // 自绘图标的补充底座（按需）

    devDependencies:
      tailwindcss: ^3.4        // design token 化样式
      postcss: ^8.4            // Tailwind 后处理
      autoprefixer: ^10.4      // 浏览器兼容

删除依赖：

    dependencies:
      element-plus             // 视觉库全弃

新增关键 TypeScript 接口（落地在 `pixflow-web/src/types/ui.ts`）：

    export type AppButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';
    export type AppButtonSize = 'sm' | 'md' | 'lg';
    export type AppBadgeTone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger';
    export type AppBadgeStyle = 'soft' | 'solid';
    export type PanelSide = 'left' | 'right';
