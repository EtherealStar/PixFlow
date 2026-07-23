/**
 * 图标系统统一出口
 *
 * 唯一权威来源：docs/design-docs/web.md §7.4
 *
 * 用法：
 *   import { IconPackage, IconImage } from '@/components/icons'
 *   <IconPackage :size="16" class="text-fg-secondary" />
 *
 * 设计原则：
 * - 8 个核心图标（节点类型 + 高频操作）自绘，与 web.md §7.4 视觉一致
 * - 其余 17 个图标从 lucide-vue-next 转发，统一 Icon* 命名空间
 * - 颜色一律走 currentColor，由父级 text-* 控制（避免每个图标硬编码颜色）
 */

// 自绘核心（8 个）
export { default as IconPackage } from './IconPackage.vue'
export { default as IconImage } from './IconImage.vue'
export { default as IconFolder } from './IconFolder.vue'
export { default as IconChat } from './IconChat.vue'
export { default as IconUpload } from './IconUpload.vue'
export { default as IconCheck } from './IconCheck.vue'
export { default as IconX } from './IconX.vue'
export { default as IconUser } from './IconUser.vue'

// lucide-vue-next 转发（17 个）
export { default as IconDownload } from './IconDownload.vue'
export { default as IconTrash } from './IconTrash.vue'
export { default as IconEdit } from './IconEdit.vue'
export { default as IconEye } from './IconEye.vue'
export { default as IconRefresh } from './IconRefresh.vue'
export { default as IconPlus } from './IconPlus.vue'
export { default as IconMinus } from './IconMinus.vue'
export { default as IconSearch } from './IconSearch.vue'
export { default as IconSend } from './IconSend.vue'
export { default as IconPaperclip } from './IconPaperclip.vue'
export { default as IconCopy } from './IconCopy.vue'
export { default as IconMoreHorizontal } from './IconMoreHorizontal.vue'
export { default as IconExternalLink } from './IconExternalLink.vue'
export { default as IconAlertCircle } from './IconAlertCircle.vue'
export { default as IconLoader } from './IconLoader.vue'
export { default as IconChevronDown } from './IconChevronDown.vue'
export { default as IconChevronRight } from './IconChevronRight.vue'
export { default as IconChevronLeft } from './IconChevronLeft.vue'
export { default as IconPin } from './IconPin.vue'
export { default as IconPinOff } from './IconPinOff.vue'

// 容器（高级用法：自绘新图标时使用）
export { default as IconBase } from './IconBase.vue'

/**
 * 图标数量清单（用于单测和走查）
 */
export const ICON_NAMES = [
  // 自绘
  'IconPackage', 'IconImage', 'IconFolder', 'IconChat',
  'IconUpload', 'IconCheck', 'IconX', 'IconUser',
  // lucide 转发
  'IconDownload', 'IconTrash', 'IconEdit', 'IconEye', 'IconRefresh',
  'IconPlus', 'IconMinus', 'IconSearch', 'IconSend', 'IconPaperclip',
  'IconCopy', 'IconMoreHorizontal', 'IconExternalLink', 'IconAlertCircle',
  'IconLoader', 'IconChevronDown', 'IconChevronRight', 'IconChevronLeft', 'IconPin', 'IconPinOff',
] as const

export type IconName = (typeof ICON_NAMES)[number]