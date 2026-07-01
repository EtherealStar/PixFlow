<script setup lang="ts">
/**
 * AppSkeleton — 骨架占位（web.md §7.2）
 *
 * bg-bg-sunken rounded-sm；可选 pulse 动画。
 */
withDefaults(
  defineProps<{
    /** 是否显示 pulse 动画 */
    pulse?: boolean
    /** 圆角档位 */
    rounded?: 'sm' | 'md' | 'lg' | 'full'
    /** 自定义 class（用于覆盖宽高 / 形状） */
    width?: string
    height?: string
  }>(),
  {
    pulse: true,
    rounded: 'sm',
  }
)
</script>

<template>
  <div
    :class="[
      'bg-bg-sunken',
      rounded === 'sm' && 'rounded-sm',
      rounded === 'md' && 'rounded-md',
      rounded === 'lg' && 'rounded-lg',
      rounded === 'full' && 'rounded-full',
      pulse && 'animate-pulse'
    ]"
    :style="{
      width: width ?? '100%',
      height: height ?? '1em'
    }"
  />
</template>