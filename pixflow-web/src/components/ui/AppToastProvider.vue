<script setup lang="ts">
import { useToastStore } from '@/stores/toast'
import IconCheck from '@/components/icons/IconCheck.vue'
import IconAlertCircle from '@/components/icons/IconAlertCircle.vue'
import IconX from '@/components/icons/IconX.vue'

/**
 * AppToastProvider — Toast 全局 Provider
 *
 * 用法（main.ts）：
 *   app.use(...) // Pinia / Router
 *   app.component('AppToastProvider', AppToastProvider)
 *   // <AppToastProvider /> 放在 <RouterView /> 后
 *
 * 视觉（web.md §7.2）：
 * - 右上角出现
 * - 自动消失 4s
 * - 可手动关闭（IconX）
 * - 4 种 variant 配色与 AppBadge 一致
 */
const toast = useToastStore()

function variantClass(variant: string): string {
  switch (variant) {
    case 'success':
      return 'bg-success-soft text-success border-success'
    case 'warning':
      return 'bg-warning-soft text-warning border-warning'
    case 'danger':
      return 'bg-danger-soft text-danger border-danger'
    case 'info':
    default:
      return 'bg-info-soft text-info border-info'
  }
}
</script>

<template>
  <div class="toast-stack" aria-live="polite" aria-atomic="false">
    <transition-group name="toast" tag="div" class="flex flex-col gap-2">
      <div
        v-for="item in toast.items"
        :key="item.id"
        :class="[
          'flex items-start gap-3',
          'min-w-[280px] max-w-[420px]',
          'px-4 py-3 rounded-md border',
          'shadow-md',
          'text-sm',
          variantClass(item.variant)
        ]"
        role="status"
      >
        <!-- 图标：success 用 check；其他用 alert -->
        <IconCheck v-if="item.variant === 'success'" :size="16" class="shrink-0 mt-0.5" />
        <IconAlertCircle v-else :size="16" class="shrink-0 mt-0.5" />

        <div class="flex-1 text-fg-primary">{{ item.message }}</div>

        <button
          type="button"
          class="shrink-0 text-fg-muted hover:text-fg-primary transition-colors"
          aria-label="关闭"
          @click="toast.dismiss(item.id)"
        >
          <IconX :size="14" />
        </button>
      </div>
    </transition-group>
  </div>
</template>

<style scoped>
.toast-stack {
  position: fixed;
  top: 16px;
  right: 16px;
  z-index: 9999;
  pointer-events: none;
}
.toast-stack > div {
  pointer-events: auto;
}
</style>

<style>
/* 局部全局样式（transition-group 动画），scoped 影响不到 */
.toast-enter-active,
.toast-leave-active {
  transition: transform 0.2s ease, opacity 0.2s ease;
}
.toast-enter-from {
  transform: translateX(20px);
  opacity: 0;
}
.toast-leave-to {
  transform: translateX(20px);
  opacity: 0;
}
</style>