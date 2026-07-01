<script setup lang="ts">
import { computed } from 'vue'
import AppCard from '@/components/ui/AppCard.vue'
import AppProgressBar from '@/components/ui/AppProgressBar.vue'
import IconCheck from '@/components/icons/IconCheck.vue'
import IconX from '@/components/icons/IconX.vue'
import IconLoader from '@/components/icons/IconLoader.vue'
import { createTask } from '@/runtime/useTask'

/**
 * TaskProgressCard — 实时进度卡（web.md §十 / §7.3）
 *
 * - 包内单任务进度展示，挂在聊天流或任务详情页
 * - 持有 createTask 实例 + 自动订阅/取消
 */
const props = defineProps<{
  taskId: string
  conversationId: string
}>()

// 懒加载建立 WS 订阅
const task = createTask({ taskId: props.taskId, conversationId: props.conversationId })
void task.attach()

const percent = computed(() => {
  const total = task.state.value.progress.total || 0
  if (total <= 0) return 0
  return Math.round((task.state.value.progress.done / total) * 100)
})

const icon = computed(() => {
  switch (task.state.value.phase) {
    case 'completed': return IconCheck
    case 'failed':
    case 'cancelled': return IconX
    default: return IconLoader
  }
})
</script>

<template>
  <AppCard padding="md" class="task-progress-card">
    <div class="flex items-center gap-2 mb-2">
      <component
        :is="icon"
        :size="16"
        :class="[
          task.state.phase === 'completed' ? 'text-success' :
          task.state.phase === 'failed' || task.state.phase === 'cancelled' ? 'text-danger' :
          'text-accent',
          task.state.phase === 'queued' || task.state.phase === 'running' ? 'animate-spin' : ''
        ]"
      />
      <span class="text-sm text-fg-primary">任务进度</span>
      <span class="text-xs text-fg-muted ml-auto">{{ percent }}%</span>
    </div>
    <AppProgressBar
      :percent="percent"
      :tone="
        task.state.phase === 'failed' ? 'danger' :
        task.state.phase === 'partial' ? 'warning' :
        task.state.phase === 'completed' ? 'success' : 'accent'
      "
    />
    <div v-if="task.state.value.error" class="mt-2 text-xs text-danger">
      错误：{{ task.state.value.error.message }}
    </div>
  </AppCard>
</template>