<script setup lang="ts">
/**
 * AppScrollArea — 自绘滚动容器（web.md §7.2）
 *
 * radix-vue 的 ScrollArea 在简单场景下用原生 overflow 即可（性能更好、无样式开销）。
 * 这里用原生 + 自定义滚动条样式（global.css 已有 webkit 滚动条）。
 *
 * 用法：
 *   <AppScrollArea max-height="300px">
 *     ...long content...
 *   </AppScrollArea>
 */
withDefaults(
  defineProps<{
    maxHeight?: string
  }>(),
  {
    maxHeight: '320px',
  }
)
</script>

<template>
  <div
    class="overflow-y-auto overflow-x-hidden pr-2"
    :style="{ maxHeight }"
  >
    <slot />
  </div>
</template>