<script setup lang="ts">
import { computed } from 'vue'
import AppCard from '@/components/ui/AppCard.vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import AppProgressBar from '@/components/ui/AppProgressBar.vue'
import type { TaskState } from '@/runtime/useTask'

/**
 * TaskCard — 任务列表行（web.md §十 / §7.3）
 *
 * 三段：标题 + phase badge / 进度条 + 百分比 / 元信息
 *
 * phase 颜色映射：进行中=info / 完成=success / 失败=danger / 取消=muted。
 */
const props = defineProps<{
  state: TaskState
  selected?: boolean
}>()

const emit = defineEmits<{
  select: [taskId: string]
}>()

const percent = computed(() => {
  const total = props.state.progress.total || 0
  if (total <= 0) return 0
  return Math.round((props.state.progress.done / total) * 100)
})

const badge = computed(() => {
  switch (props.state.phase) {
    case 'queued':
      return { tone: 'muted' as const, label: '排队中' }
    case 'running':
      return { tone: 'info' as const, label: '进行中' }
    case 'completed':
      return { tone: 'success' as const, label: '已完成' }
    case 'failed':
      return { tone: 'danger' as const, label: '失败' }
    case 'partial':
      return { tone: 'warning' as const, label: '部分成功' }
    case 'cancelled':
      return { tone: 'muted' as const, label: '已取消' }
  }
  return { tone: 'muted' as const, label: '未知' }
})

const barTone = computed(() => {
  switch (props.state.phase) {
    case 'failed':
      return 'danger' as const
    case 'partial':
      return 'warning' as const
    case 'completed':
      return 'success' as const
    default:
      return 'accent' as const
  }
})
</script>

<template>
  <AppCard
    hoverable
    padding="sm"
    :class="['task-card cursor-pointer', selected ? 'ring-2 ring-accent' : '']"
    @click="emit('select', state.taskId)"
  >
    <div class="flex items-start justify-between gap-2 mb-2">
      <div class="min-w-0 flex-1">
        <div class="text-sm font-medium text-fg-primary truncate">
          {{ state.taskType ?? '图像处理' }} · {{ state.taskId.slice(0, 8) }}
        </div>
        <div class="text-xs text-fg-muted truncate">
          {{ state.progress.done }}/{{ state.progress.total }}
          <span v-if="state.progress.failed > 0" class="text-danger">
            · 失败 {{ state.progress.failed }}
          </span>
        </div>
      </div>
      <AppBadge :tone="badge.tone" style="solid">{{ badge.label }}</AppBadge>
    </div>
    <AppProgressBar :percent="percent" :tone="barTone" />
  </AppCard>
</template>