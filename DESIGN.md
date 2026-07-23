---
name: PixFlow
description: 对话即批量图片流水线——电商运营助理 Agent 的工作台
colors:
  primary: "#4F46E5"
  primary-hover: "#6366F1"
  primary-soft: "#EEF2FF"
  bg-page: "#FAFAF9"
  bg-sunken: "#F5F5F4"
  bg-panel: "#FFFFFF"
  border: "#E7E5E4"
  border-strong: "#D6D3D1"
  fg-primary: "#1C1917"
  fg-secondary: "#57534E"
  fg-muted: "#A8A29E"
  fg-inverse: "#FFFFFF"
  success: "#10B981"
  success-soft: "#ECFDF5"
  warning: "#F59E0B"
  warning-soft: "#FFFBEB"
  danger: "#EF4444"
  danger-soft: "#FEF2F2"
  info: "#3B82F6"
  info-soft: "#EFF6FF"
typography:
  display:
    fontFamily: "system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif"
    fontSize: "30px"
    fontWeight: 600
    lineHeight: 1.25
  headline:
    fontFamily: "system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif"
    fontSize: "24px"
    fontWeight: 600
    lineHeight: 1.3
  title:
    fontFamily: "system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: 1.5
  body:
    fontFamily: "system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif"
    fontSize: "12px"
    fontWeight: 500
    lineHeight: 1.5
rounded:
  sm: "6px"
  md: "10px"
  lg: "14px"
  xl: "20px"
  pill: "9999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "32px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.fg-inverse}"
    rounded: "{rounded.md}"
    padding: "0 16px"
    height: "40px"
    typography: "{typography.body}"
  button-primary-hover:
    backgroundColor: "{colors.primary-hover}"
  button-secondary:
    backgroundColor: "{colors.bg-panel}"
    textColor: "{colors.fg-primary}"
    rounded: "{rounded.md}"
    padding: "0 16px"
    height: "40px"
  button-ghost:
    backgroundColor: "transparent"
    textColor: "{colors.fg-primary}"
    rounded: "{rounded.md}"
    padding: "0 16px"
    height: "40px"
  button-danger:
    backgroundColor: "{colors.danger}"
    textColor: "{colors.fg-inverse}"
    rounded: "{rounded.md}"
    padding: "0 16px"
    height: "40px"
  input:
    backgroundColor: "{colors.bg-panel}"
    textColor: "{colors.fg-primary}"
    rounded: "{rounded.md}"
    padding: "0 12px"
    height: "40px"
  badge-soft:
    backgroundColor: "{colors.primary-soft}"
    textColor: "{colors.primary}"
    rounded: "{rounded.pill}"
    padding: "2px 8px"
    typography: "{typography.label}"
  card:
    backgroundColor: "{colors.bg-panel}"
    rounded: "{rounded.lg}"
    padding: "16px"
  nav-link:
    textColor: "{colors.fg-secondary}"
    rounded: "{rounded.sm}"
    padding: "8px 10px"
  nav-link-active:
    backgroundColor: "{colors.primary-soft}"
    textColor: "{colors.fg-primary}"
    rounded: "{rounded.sm}"
    padding: "8px 10px"
---

# Design System: PixFlow

## 1. Overview

**Creative North Star: 「安静的流水线」(The Quiet Pipeline)**

界面退后,工作状态自己说话。PixFlow 的设计系统不为「好看」服务,为「确认」服务:运营人员扫一眼就知道任务进行到哪一步、哪张图成功了、哪个环节失败了、下一步该点哪里。色彩、阴影、动效全部只在传达状态时才被允许出现,其余时刻保持安静。

这套系统明确拒绝三种气质:营销 SaaS 官网风(大 hero、渐变标题、玻璃拟态)、中后台模板风(Ant Design Pro / Element Admin 式默认蓝白表格后台)、AI 炫技风(发光边框、粒子背景、紫蓝霓虹渐变)。它的参照系是运营人员每天重复使用的专业工具:密度合理、反馈即时、词汇熟悉。单管理员鉴权是演示阶段的约束,界面上永远看不见它。

**Key Characteristics:**

- 三层暖石灰背景递进(page → sunken → panel),层级靠底色而非卡片堆叠
- 信号靛蓝一声部:只为主操作、当前选中、焦点态发声
- 微动反馈:hover 抬升 1px、按下 scale-0.98、全部 150ms 内完成
- 固定紧凑字阶:页面标题 14px semibold 是常态,不用流式 clamp
- 圆角四档(6 / 10 / 14 / 20),胶囊全圆角仅徽章专用

## 2. Colors: 暖石灰 × 信号靛蓝

一个主色,一族中性色,一组状态色——没有第三种声音。

### Primary

- **信号靛蓝 Signal Indigo** (#4F46E5):主操作按钮底色、当前导航选中的 soft 底、输入框焦点边框与焦点环、链接态状态指示。它的稀有就是它的力量:用户看到靛蓝就知道「这里可操作」或「这是当前位置」。悬停转亮 (#6366F1),浅底 (#EEF2FF) 只用于选中/悬停背景与 ::selection 选区。

### Tertiary(状态语义族)

状态色不是装饰色,它们只在 Activity 卡片、徽章、进度、错误文案中作为语义出现,且永远搭配文字,不做唯一信号。

- **成功绿** (#10B981,浅底 #ECFDF5):已成功状态、成功计数。
- **警告琥珀** (#F59E0B,浅底 #FFFBEB):部分成功、需注意状态。
- **失败红** (#EF4444,浅底 #FEF2F2):失败状态、危险操作(删除)、表单错误。
- **信息蓝** (#3B82F6,浅底 #EFF6FF):进行中与一般提示。

### Neutral

- **暖石灰 Warm Stone** 一族承载全部底色、文本与边框,微暖但不发黄。三层背景递进:整页底色 (#FAFAF9) → 凹陷容器/侧栏 (#F5F5F4) → 卡片与浮层 (#FFFFFF)。文本四档:主文本 (#1C1917) → 次文本 (#57534E) → 辅助文本 (#A8A29E) → 反白 (#FFFFFF)。边框两档:默认 (#E7E5E4)、强调 (#D6D3D1),滚动条拇指同族。

### Named Rules

**The One Voice Rule(一声部规则).** 信号靛蓝在任何单屏的占比不超过 10%,只服务于主操作、当前选中与焦点态。如果一屏出现第二处「装饰性靛蓝」,它就是噪音,删掉。

**The Three-Layer Ground Rule(三层地基规则).** 背景只允许 page → sunken → panel 由浅到深递进,禁止引入第四种底色,禁止把 panel 直接压在 page 上充当分组容器——需要分组时先落 sunken。

**The Warm-Only Neutrals Rule(暖灰唯一规则).** 中性色只取 stone 一族。禁止混入 gray / zinc / neutral 任何一档,禁止纯黑 #000——包括阴影基色(统一 rgba(28,25,23,…))。

## 3. Typography

**Display / Body / Label Font:** 单一系统字栈 system-ui → -apple-system → Segoe UI → PingFang SC → Microsoft YaHei(中文由 PingFang SC / Microsoft YaHei 接管)。

**Character:** 一个家族打全场。标题与正文的区分靠字重(400 / 500 / 600)与字阶,不靠第二个字体;产品界面不引入展示字体。

### Hierarchy

- **Display** (600, 30px, 1.25):仅欢迎区与空状态主标题,一屏至多一处。
- **Headline** (600, 24px, 1.3):登录页、错误页等独立大标题。
- **Title** (600, 14px, 1.5):页面 h1 与区块标题的常态——产品标题保持紧凑,不放大。
- **Body** (400, 14px, 1.5):正文、列表、表单、对话消息;15px 变体仅用于大尺寸控件文字。
- **Label** (500, 12–13px, 1.5):徽章、按钮、辅助说明、元数据。

### Named Rules

**The Fixed Scale Rule(固定字阶规则).** 八档固定字阶(12 / 13 / 14 / 15 / 17 / 20 / 24 / 30)覆盖全部场景,禁止 clamp() 流式字号——用户在固定 DPI 下工作,标题不该随窗口呼吸。

**The One Family Rule(单一家族规则).** 任何界面文本不得引入第二字体家族。需要强调时用 600 字重或上移一档字阶,永远不靠换字体。

## 4. Elevation

混合策略:层级默认由三层背景的色调递进承担,阴影不是装饰,是状态的语言。静置表面是平的;阴影只回应两件事——交互(hover / focus)与浮层(dialog / popover / drawer)。

### Shadow Vocabulary

- **Resting** (`0 1px 2px rgba(28,25,23,0.04), 0 1px 3px rgba(28,25,23,0.06)`):卡片与按钮的静置态,几乎不可感知,仅把白底从 sunken 上托起。
- **Lifted** (`0 4px 12px rgba(28,25,23,0.06), 0 2px 4px rgba(28,25,23,0.04)`):hover 反馈,配合 1px 抬升同时出现。
- **Overlay** (`0 12px 32px rgba(28,25,23,0.08), 0 4px 8px rgba(28,25,23,0.04)`):对话框、抽屉、浮层唯一允许的大阴影。

### Named Rules

**The Flat-by-Default Rule(默认平面规则).** 表面静置时是平的。阴影随状态出现、随状态消失;任何「为了好看而常驻」的阴影都在违反流水线安静的纪律。

**The Warm Shadow Rule(暖影规则).** 阴影基色统一 rgba(28,25,23,…) 透明度 4%–8%,禁止纯黑阴影、禁止 Tailwind 默认 shadow 黑基。

## 5. Components

### Buttons

四种变体(primary / secondary / ghost / danger)× 三档尺寸(h-8 / h-10 / h-12),手感统一:沉稳有形,微动反馈。

- **Shape:** 中等圆角(10px),默认高度 40px、水平 padding 16px、14px 字、500 字重。
- **Primary:** 信号靛蓝底 + 反白字;hover 转亮 (#6366F1) 同时阴影 Resting → Lifted、抬升 1px。
- **Hover / Focus / Active:** 全部过渡 150ms;focus-visible 为 2px 靛蓝 20% 透明环 + 边框转靛蓝;active 整体 scale-0.98 轻按回弹。
- **Secondary / Ghost / Danger:** secondary 白底 + 1px 边框,hover 落 sunken;ghost 透明,hover 落 sunken;danger 失败红底,仅用于不可逆删除。
- **Loading / Disabled:** loading 右侧 spinner 并禁用点击;disabled 透明度 50%,不抬升、不回弹。

### Chips / Badges

- **Style:** soft(浅底 + 同族深字)为默认,solid(实心底 + 反白字)仅用于需要跳出的状态;5 种 tone(accent / success / warning / danger / neutral)与状态语义族一一对应。
- **Shape:** 胶囊全圆角(9999px)——这是全系统唯一的全圆角例外;直角变体退到 6px。12px 字、500 字重、padding 2px 8px。

### Cards / Containers

- **Corner Style:** 大圆角(14px)。
- **Background:** 白色 panel,落在 sunken 或 page 之上;可选 1px 默认边框。
- **Shadow Strategy:** 静置 Resting;仅 hoverable 卡片 hover 升 Lifted(见 Elevation)。
- **Internal Padding:** 三档(12 / 16 / 24px),默认 16px。
- **Use:** 卡片只用于重复项、Proposal 记录、Activity 记录;页面区块不靠卡片分组,禁止嵌套卡片。

### Inputs / Fields

- **Style:** 白底 + 1px 默认边框,中等圆角(10px),高 40px,14px 字;prefix / suffix 图标位取辅助文本色。
- **Focus:** 边框转信号靛蓝 + 2px 靛蓝 20% 透明环,焦点落在壳上(focus-within)而非输入框本体。
- **Error / Disabled:** error 为红边 + 红色 20% 环 + 警示图标 + 下方 12px 错误文案;disabled 落 sunken 底、透明度 50%。

### Navigation

- **Style:** 左栏导航项,小圆角(6px),padding 8px 10px,14px 字,图标与文字 8px 间距。
- **States:** 默认次文本色;hover 落 sunken 底、转主文本色;active 落靛蓝浅底、字重升 500——当前位置靠「浅底 + 加重」表达,不靠左侧色条。

## 6. Do's and Don'ts

### Do:

- **Do** 颜色全部走 token(Tailwind 语义类或 `var(--bg-panel)` 等 CSS 变量),业务组件禁止硬编码 hex。
- **Do** 用三层背景递进表达层级;需要分组时先落 sunken,而不是加卡片、加边框。
- **Do** 状态文案使用 product.md 的规范术语(上传中 / 解析中 / 已成功 / 已失败),失败可附可复制的错误编号。
- **Do** 动效控制在 150–250ms、ease-out、只传达状态;所有动效提供 reduced-motion 备选。
- **Do** 保持焦点可见:focus-visible 用靛蓝 20% 环或 2px outline,键盘可达是 WCAG 2.1 AA 的硬要求。

### Don't:

- **Don't** 营销 SaaS 官网风:大 hero 区、渐变标题文字、玻璃拟态卡片、每节一个小写 eyebrow——PRODUCT.md 点名的反面,出现即返工。
- **Don't** 中后台模板风:把信号靛蓝换成 Ant Design Pro / Element Admin 式默认蓝,或摆出蓝白表格后台的默认骨架。
- **Don't** AI 炫技风:发光边框、粒子背景、紫蓝霓虹渐变、为炫技而生的动效。
- **Don't** 引入暗色分支(darkMode 已显式禁用)、emoji 图标、纯黑阴影、gray / zinc / neutral 色系。
- **Don't** 大于 1px 的彩色侧条边框、嵌套卡片、展示字体进按钮与标签、大于 10% 屏占比的靛蓝。
