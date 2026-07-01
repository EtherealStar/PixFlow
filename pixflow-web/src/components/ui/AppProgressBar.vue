<script setup lang="ts">
import { computed } from 'vue'

/**
 * AppProgressBar — 自绘进度条（web.md §7.2）
 *
 * - 圆角胶囊（rounded-full）
 * - 轨道 bg-bg-sunken
 * - 填充 bg-accent（可用 tone 覆盖为 success/danger）
 * - 高度 6px
 * - 平滑过渡 200ms
 */
const props = withDefaults(
  defineProps<{
    /** 百分比 0-100 */
    percent?: number
    /** 进度条色调 */
    tone?: 'accent' | 'success' | 'danger' | 'warning'
    /** 高度 px */
    height?: number
  }>(),
  {
    percent: 0,
    tone: 'accent',
    height: 6,
  }
)

// 防止百分比越界
const safePercent = computed(() => Math.max(0, Math.min(100, props.percent)))

const barColor = computed(() => {
  switch (props.tone) {
    case 'success':
      return 'bg-success'
    case 'danger':
      return 'bg-danger'
    case 'warning':
      return 'bg-warning'
    case 'accent':
    default:
      return 'bg-accent'
  }
})
</script>

<template>
  <div
    class="w-full bg-bg-sunken rounded-full overflow-hidden"
    :style="{ height: `${height}px` }"
    role="progressbar"
    :aria-valuenow="safePercent"
    aria-valuemin="0"
    aria-valuemax="100"
  >
    <div
      :class="['h-full rounded-full transition-[width] duration-200 ease-out', barColor]"
      :style="{ width: `${safePercent}%` }"
    />
  </div>
</template>