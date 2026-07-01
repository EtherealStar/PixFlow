<script setup lang="ts">
import { computed } from 'vue'
import type { AppBadgeStyle, AppBadgeTone } from '@/types/ui'

/**
 * AppBadge — 自绘徽章（web.md §7.2）
 *
 * 5 种 tone × 2 种 style：
 * - soft：浅色背景（*-soft）+ 深色文字（*-DEFAULT）
 * - solid：实心背景（*-DEFAULT）+ 反白文字（fg-inverse）
 *
 * 圆角：rounded-full（默认）或 rounded-sm
 */
const props = withDefaults(
  defineProps<{
    tone?: AppBadgeTone
    style?: AppBadgeStyle
    /** 是否圆角胶囊（默认 true） */
    rounded?: boolean
  }>(),
  {
    tone: 'neutral',
    style: 'soft',
    rounded: true,
  }
)

const toneClass = computed(() => {
  if (props.style === 'solid') {
    // 实心：背景 tone，文字 fg-inverse
    switch (props.tone) {
      case 'accent':
        return 'bg-accent text-fg-inverse border-accent'
      case 'success':
        return 'bg-success text-fg-inverse border-success'
      case 'warning':
        return 'bg-warning text-fg-inverse border-warning'
      case 'danger':
        return 'bg-danger text-fg-inverse border-danger'
      case 'neutral':
      default:
        return 'bg-fg-secondary text-fg-inverse border-fg-secondary'
    }
  }
  // soft：浅色背景，深色文字
  switch (props.tone) {
    case 'accent':
      return 'bg-accent-soft text-accent border-accent-soft'
    case 'success':
      return 'bg-success-soft text-success border-success-soft'
    case 'warning':
      return 'bg-warning-soft text-warning border-warning-soft'
    case 'danger':
      return 'bg-danger-soft text-danger border-danger-soft'
    case 'neutral':
    default:
      return 'bg-bg-sunken text-fg-secondary border-bg-sunken'
  }
})
</script>

<template>
  <span
    :class="[
      'inline-flex items-center justify-center',
      'px-2 py-0.5 text-xs font-medium border',
      rounded ? 'rounded-full' : 'rounded-sm',
      toneClass
    ]"
  >
    <slot />
  </span>
</template>