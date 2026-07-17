<script setup lang="ts">
import { computed } from 'vue'

/**
 * AppAvatar — 自绘头像（web.md §7.2）
 *
 * - 圆形 32px
 * - bg-bg-sunken 占位
 * - 首字 text-fg-secondary
 * - 支持图片 URL（src 传入）
 */
const props = withDefaults(
  defineProps<{
    /** 显示文本（如用户名首字母） */
    text?: string
    /** 图片 URL（优先级高于 text） */
    src?: string
    /** 尺寸 px */
    size?: number
  }>(),
  {
    text: undefined,
    src: undefined,
    size: 32,
  }
)

// 取首字符（中文取首字，英文取首字母）
const initial = computed(() => {
  if (!props.text) return ''
  // 用 codePoint 处理中文等多字节字符
  const first = Array.from(props.text)[0]
  return first ? first.toUpperCase() : ''
})
</script>

<template>
  <div
    :class="[
      'inline-flex items-center justify-center',
      'rounded-full bg-bg-sunken',
      'overflow-hidden shrink-0',
      'border border-border'
    ]"
    :style="{ width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size * 0.45)}px` }"
  >
    <img
      v-if="src"
      :src="src"
      alt=""
      class="w-full h-full object-cover"
    >
    <span
      v-else-if="initial"
      class="text-fg-secondary font-medium select-none"
    >{{ initial }}</span>
  </div>
</template>
