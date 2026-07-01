/**
 * UI 通用类型
 * 唯一权威来源：docs/design-docs/web.md §7
 */

/** AppButton 视觉变体（4 种，详见 web.md §7.2） */
export type AppButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'

/** AppButton 尺寸（3 种） */
export type AppButtonSize = 'sm' | 'md' | 'lg'

/** AppBadge tone（5 种） */
export type AppBadgeTone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger'

/** AppBadge 风格：soft = 浅色背景 + 深色字；solid = 实心背景 + 反白字 */
export type AppBadgeStyle = 'soft' | 'solid'

/** AppToast variant */
export type AppToastVariant = 'info' | 'success' | 'warning' | 'danger'

/** 侧栏位置（用于面板折叠/钉住语义） */
export type PanelSide = 'left' | 'right'
