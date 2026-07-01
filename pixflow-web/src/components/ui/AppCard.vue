<script setup lang="ts">
/**
 * AppCard — 自绘卡片容器（web.md §7.2）
 *
 * 视觉：
 * - 白底 bg-bg-panel
 * - 默认 shadow-sm，hover 升到 shadow-md（hoverable=true）
 * - 圆角 lg（14px）
 * - 可选 border border-border
 */
withDefaults(
  defineProps<{
    /** 是否启用 hover 阴影提升 */
    hoverable?: boolean
    /** 是否显示边框 */
    bordered?: boolean
    /** 内部 padding 档位 */
    padding?: 'none' | 'sm' | 'md' | 'lg'
  }>(),
  {
    hoverable: false,
    bordered: false,
    padding: 'md',
  }
)
</script>

<template>
  <div
    :class="[
      'bg-bg-panel rounded-lg',
      'transition-shadow duration-150',
      hoverable ? 'shadow-sm hover:shadow-md' : 'shadow-sm',
      bordered && 'border border-border',
      padding === 'none' && 'p-0',
      padding === 'sm' && 'p-3',
      padding === 'md' && 'p-4',
      padding === 'lg' && 'p-6'
    ]"
  >
    <slot />
  </div>
</template>