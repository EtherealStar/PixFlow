# 前端 UI 与视觉系统设计

## 定位

UI 视觉模块承载 PixFlow Web 的浅色主题、design token、自绘 UI 原子、radix-vue headless 包装和 SVG 图标系统。所有页面和业务组件都应通过本视觉层构建，不直接恢复旧视觉库。

## 关键实现

- Tailwind token：`pixflow-web/tailwind.config.ts`
- 全局样式：`src/styles/global.css`、`src/styles/tokens.css`
- UI 原子：`src/components/ui/`
- 布局组件：`src/components/layout/`
- 图标：`src/components/icons/`
- 类型：`src/types/ui.ts`

## 视觉原则

1. 只支持浅色主题，不引入 dark mode 或 `prefers-color-scheme` 分支。
2. 背景三层递进：`bg-page`、`bg-sunken`、`bg-panel`。
3. 暖灰基调，主色只用于主操作、进度条和选中态。
4. 阴影使用暖灰透明阴影，不使用纯黑阴影。
5. 圆角只使用 `sm/md/lg/xl` 四档。
6. 产品界面只使用 SVG 图标，禁止 emoji。

## UI 原子

| 类别 | 组件 |
|---|---|
| 表单 | `AppButton`、`AppInput`、`AppTextarea`、`AppSelect`、`AppSwitch`、`AppCheckbox` |
| 容器 | `AppCard`、`AppBadge`、`AppAvatar`、`AppProgressBar`、`AppSegmented`、`AppEmptyState`、`AppSkeleton` |
| 浮层 | `AppDialog`、`AppDropdownMenu`、`AppPopover`、`AppTooltip`、`AppToastProvider` |
| 导航/结构 | `AppTabs`、`AppTabsTrigger`、`AppTabsPanel`、`AppAccordion`、`AppAccordionItem`、`AppScrollArea` |
| 全局 | `TraceIdFloat` |

radix-vue 只提供行为、可访问性和浮层原语；视觉样式由 App 组件自己负责。

## 图标系统

`src/components/icons/` 提供 lucide 风格自绘 SVG，统一 24x24、stroke 1.75、`currentColor`。业务组件应从本目录导入图标；只有确实缺少图标时才按需使用 `lucide-vue-next` 作为底座补充。

## 约束

1. 新组件优先落在 `components/ui/`，业务组合落在 `components/{chat,files,tasks,upload}/`。
2. 不在业务组件中硬编码 hex 颜色；使用 Tailwind token 类。
3. 不引入新视觉库来解决单个组件问题。
4. 不在产品界面使用 emoji 文本或 emoji 字体。
5. 修改 token 后必须检查 `web.md` §五的不变量和现有页面可读性。
