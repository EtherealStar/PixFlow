<script setup lang="ts">
import { computed } from 'vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import AppButton from '@/components/ui/AppButton.vue'
import IconRefresh from '@/components/icons/IconRefresh.vue'
import IconTrash from '@/components/icons/IconTrash.vue'
import IconExternalLink from '@/components/icons/IconExternalLink.vue'
import { RotateCcw } from 'lucide-vue-next'
import type { TaskState } from '@/runtime/useTask'

/**
 * TaskDetailHeader — 任务详情页头（web.md §十 / §7.3）
 *
 * 行内：标题 / phase badge / taskId / 元信息 / 操作按钮
 */
const props = defineProps<{
  state: TaskState
}>()

const emit = defineEmits<{
  refresh: []
  cancel: []
  retry: []
  openExternal: []
}>()

const badge = computed(() => {
  switch (props.state.phase) {
    case 'queued': return { tone: 'muted' as const, label: '排队中' }
    case 'running': return { tone: 'info' as const, label: '进行中' }
    case 'completed': return { tone: 'success' as const, label: '已完成' }
    case 'failed': return { tone: 'danger' as const, label: '失败' }
    case 'partial': return { tone: 'warning' as const, label: '部分成功' }
    case 'cancelled': return { tone: 'muted' as const, label: '已取消' }
    default: return { tone: 'muted' as const, label: '未知' }
  }
})

const canCancel = computed(() => props.state.phase === 'queued' || props.state.phase === 'running')
const canRetry = computed(() =>
  (props.state.phase === 'failed' || props.state.phase === 'partial') && props.state.progress.failed > 0
)

function fmt(ts?: string): string {
  if (!ts) return '—'
  const d = new Date(ts)
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString()
}
</script>

<template>
  <header class="task-detail-header flex flex-col gap-3 px-6 py-5 border-b border-border bg-bg-panel">
    <div class="flex items-start justify-between gap-3">
      <div class="min-w-0 flex-1">
        <div class="flex items-center gap-2 mb-1">
          <h2 class="text-lg font-medium text-fg-primary truncate">
            {{ state.taskType ?? '图像处理' }}
          </h2>
          <AppBadge
            :tone="badge.tone"
            style="solid"
          >
            {{ badge.label }}
          </AppBadge>
        </div>
        <div class="text-xs font-mono text-fg-muted">
          #{{ state.taskId }}
        </div>
      </div>
      <div class="flex items-center gap-2 shrink-0">
        <AppButton
          variant="ghost"
          size="sm"
          @click="emit('refresh')"
        >
          <IconRefresh
            :size="14"
            class="mr-1"
          />
          刷新
        </AppButton>
        <AppButton
          v-if="canCancel"
          variant="ghost"
          size="sm"
          @click="emit('cancel')"
        >
          <IconTrash
            :size="14"
            class="mr-1"
          />
          取消
        </AppButton>
        <AppButton
          v-if="canRetry"
          variant="primary"
          size="sm"
          @click="emit('retry')"
        >
          <RotateCcw
            :size="14"
            class="mr-1"
          />
          重试失败项
        </AppButton>
        <AppButton
          variant="ghost"
          size="sm"
          @click="emit('openExternal')"
        >
          <IconExternalLink
            :size="14"
            class="mr-1"
          />
          新窗口
        </AppButton>
      </div>
    </div>
    <dl class="grid grid-cols-2 md:grid-cols-4 gap-3 text-xs">
      <div>
        <dt class="text-fg-muted mb-0.5">
          已处理 / 总数
        </dt>
        <dd class="text-fg-primary">
          {{ state.progress.done }} / {{ state.progress.total }}
        </dd>
      </div>
      <div>
        <dt class="text-fg-muted mb-0.5">
          失败
        </dt>
        <dd class="text-fg-primary">
          {{ state.progress.failed }}
        </dd>
      </div>
      <div>
        <dt class="text-fg-muted mb-0.5">
          跳过
        </dt>
        <dd class="text-fg-primary">
          {{ state.progress.skipped }}
        </dd>
      </div>
      <div>
        <dt class="text-fg-muted mb-0.5">
          开始时间
        </dt>
        <dd class="text-fg-primary">
          {{ fmt(state.startedAt) }}
        </dd>
      </div>
      <div>
        <dt class="text-fg-muted mb-0.5">
          完成时间
        </dt>
        <dd class="text-fg-primary">
          {{ fmt(state.finishedAt) }}
        </dd>
      </div>
    </dl>
  </header>
</template>
