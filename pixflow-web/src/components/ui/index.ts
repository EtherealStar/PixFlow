/**
 * ui 视觉原子统一出口
 * 唯一权威来源：docs/design-docs/web.md §7.2
 */

// 表单类
export { default as AppButton } from './AppButton.vue'
export { default as AppInput } from './AppInput.vue'
export { default as AppTextarea } from './AppTextarea.vue'
export { default as AppSelect } from './AppSelect.vue'
export { default as AppSwitch } from './AppSwitch.vue'

// 容器类
export { default as AppCard } from './AppCard.vue'
export { default as AppBadge } from './AppBadge.vue'
export { default as AppAvatar } from './AppAvatar.vue'
export { default as AppProgressBar } from './AppProgressBar.vue'
export { default as AppSegmented } from './AppSegmented.vue'
export { default as AppEmptyState } from './AppEmptyState.vue'
export { default as AppSkeleton } from './AppSkeleton.vue'
export { default as AppScrollArea } from './AppScrollArea.vue'

// 浮层类
export { default as AppDialog } from './AppDialog.vue'
export { default as AppTooltip } from './AppTooltip.vue'
export { default as AppPopover } from './AppPopover.vue'
export { default as AppDropdownMenu } from './AppDropdownMenu.vue'
export { default as AppDropdownMenuItem } from './AppDropdownMenuItem.vue'
export { default as AppDropdownMenuSeparator } from './AppDropdownMenuSeparator.vue'
export { default as AppToastProvider } from './AppToastProvider.vue'
export { default as AppTabs } from './AppTabs.vue'
export { default as AppTabsTrigger } from './AppTabsTrigger.vue'
export { default as AppTabsPanel } from './AppTabsPanel.vue'
export { default as AppAccordion } from './AppAccordion.vue'
export { default as AppAccordionItem } from './AppAccordionItem.vue'

export const UI_COMPONENT_NAMES = [
  'AppButton', 'AppInput', 'AppTextarea', 'AppSelect', 'AppSwitch',
  'AppCard', 'AppBadge', 'AppAvatar', 'AppProgressBar', 'AppSegmented',
  'AppEmptyState', 'AppSkeleton', 'AppScrollArea',
  'AppDialog', 'AppTooltip', 'AppPopover', 'AppDropdownMenu',
  'AppDropdownMenuItem', 'AppDropdownMenuSeparator', 'AppToastProvider',
  'AppTabs', 'AppTabsTrigger', 'AppTabsPanel', 'AppAccordion', 'AppAccordionItem',
] as const