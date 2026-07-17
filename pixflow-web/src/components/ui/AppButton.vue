<script setup lang="ts">
import { computed } from 'vue'
import IconLoader from '@/components/icons/IconLoader.vue'
import type { AppButtonSize, AppButtonVariant } from '@/types/ui'

/**
 * AppButton — 自绘按钮（web.md §7.2）
 *
 * 视觉规则（与 §5.4 不变量一致）：
 * - 4 种 variant × 3 种 size
 * - 圆角 md（10px）；hover 阴影从 sm 升到 md + translate-y-[-1px] 微抬升
 * - 按下 active:scale-[0.98]
 * - loading 态右侧 spinner（IconLoader + animate-spin）
 * - disabled 态 opacity-50 + cursor-not-allowed
 *
 * 设计原则：
 * - 颜色全部走 token（bg-accent / bg-bg-panel / bg-danger 等），禁止硬编码 hex
 * - 阴影走 shadow-sm / shadow-md，禁止 shadow-lg shadow-black/20
 */
const props = withDefaults(
  defineProps<{
    /** 视觉变体 */
    variant?: AppButtonVariant
    /** 尺寸 */
    size?: AppButtonSize
    /** loading 态（右侧 spinner + 禁用点击） */
    loading?: boolean
    /** 禁用态 */
    disabled?: boolean
    /** 原生 button type */
    type?: 'button' | 'submit' | 'reset'
  }>(),
  {
    variant: 'secondary',
    size: 'md',
    loading: false,
    disabled: false,
    type: 'button',
  }
)

defineEmits<{
  click: [ev: MouseEvent]
}>()

// 尺寸类（高度、水平 padding、字号）
const sizeClass = computed(() => {
  switch (props.size) {
    case 'sm':
      return 'h-8 px-3 text-sm'
    case 'lg':
      return 'h-12 px-5 text-md'
    case 'md':
    default:
      return 'h-10 px-4 text-base'
  }
})

// variant 类（背景 / 文字 / 边框）
const variantClass = computed(() => {
  switch (props.variant) {
    case 'primary':
      return 'bg-accent text-fg-inverse border-accent hover:bg-accent-hover hover:border-accent-hover'
    case 'danger':
      return 'bg-danger text-fg-inverse border-danger hover:opacity-90'
    case 'ghost':
      return 'bg-transparent text-fg-primary border-transparent hover:bg-bg-sunken'
    case 'secondary':
    default:
      return 'bg-bg-panel text-fg-primary border-border hover:bg-bg-sunken'
  }
})
</script>

<template>
  <button
    :type="type"
    :disabled="disabled || loading"
    :class="[
      'inline-flex items-center justify-center gap-2',
      'rounded-md border',
      'font-medium',
      'transition-[box-shadow,transform,background-color] duration-150',
      'shadow-sm hover:shadow-md',
      'active:scale-[0.98]',
      'disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:shadow-sm disabled:active:scale-100',
      'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/20 focus-visible:border-accent',
      sizeClass,
      variantClass
    ]"
    @click="(ev: MouseEvent) => $emit('click', ev)"
  >
    <IconLoader
      v-if="loading"
      :size="size === 'sm' ? 14 : size === 'lg' ? 18 : 16"
      class="animate-spin"
    />
    <slot />
  </button>
</template>