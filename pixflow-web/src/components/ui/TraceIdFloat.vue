<script setup lang="ts">
import { computed } from 'vue'
import { useUiStore } from '@/stores/ui'
import { useToastStore } from '@/stores/toast'
import IconCopy from '@/components/icons/IconCopy.vue'

/**
 * TraceIdFloat — 右下角固定浮窗（web.md §二十二 traceId 浮窗）
 *
 * 视觉：
 * - bg-bg-panel shadow-md rounded-md p-2
 * - text-xs
 * - 点击复制到剪贴板（用 IconCopy）
 *
 * 通过 AppToast 反馈复制结果（不再用 R1 阶段的 console 兜底）
 */
const ui = useUiStore()
const toast = useToastStore()

const traceId = computed(() => ui.floatingTraceId ?? '—')

async function copy(): Promise<void> {
  if (!ui.floatingTraceId) {
    toast.push({ variant: 'info', message: '暂无 traceId' })
    return
  }
  try {
    await navigator.clipboard.writeText(ui.floatingTraceId)
    toast.push({ variant: 'success', message: 'traceId 已复制' })
  } catch {
    toast.push({ variant: 'danger', message: '复制失败' })
  }
}
</script>

<template>
  <div
    v-if="ui.floatingTraceId"
    class="trace-float flex items-center gap-2 bg-bg-panel shadow-md rounded-md px-3 py-2 text-xs font-mono cursor-pointer hover:shadow-lg transition-shadow"
    role="button"
    aria-label="点击复制 traceId"
    @click="copy"
  >
    <span class="text-fg-muted">traceId</span>
    <span class="text-fg-primary max-w-[240px] overflow-hidden text-ellipsis whitespace-nowrap">
      {{ traceId }}
    </span>
    <IconCopy
      :size="12"
      class="text-fg-muted shrink-0"
    />
  </div>
</template>

<style scoped>
.trace-float {
  position: fixed;
  right: 12px;
  bottom: 12px;
  z-index: 1000;
}
</style>