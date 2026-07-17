<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
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
onMounted(() => {
  void task.refresh()
  task.subscribeWS()
})
onBeforeUnmount(() => task.unsubscribeWS())

const currentState = computed(() => task.state.value)

const percent = computed(() => {
  const total = currentState.value.progress.total || 0
  if (total <= 0) return 0
  return Math.round((currentState.value.progress.done / total) * 100)
})

const icon = computed(() => {
  switch (currentState.value.phase) {
    case 'completed': return IconCheck
    case 'failed':
    case 'cancelled': return IconX
    default: return IconLoader
  }
})
</script>

<template>
  <AppCard
    padding="md"
    class="task-progress-card"
  >
    <div class="flex items-center gap-2 mb-2">
      <component
        :is="icon"
        :size="16"
        :class="[
          currentState.phase === 'completed' ? 'text-success' :
          currentState.phase === 'failed' || currentState.phase === 'cancelled' ? 'text-danger' :
          'text-accent',
          currentState.phase === 'queued' || currentState.phase === 'running' ? 'animate-spin' : ''
        ]"
      />
      <span class="text-sm text-fg-primary">任务进度</span>
      <span class="text-xs text-fg-muted ml-auto">{{ percent }}%</span>
    </div>
    <AppProgressBar
      :percent="percent"
      :tone="
        currentState.phase === 'failed' ? 'danger' :
        currentState.phase === 'partial' ? 'warning' :
        currentState.phase === 'completed' ? 'success' : 'accent'
      "
    />
    <div
      v-if="currentState.error || currentState.lastError"
      class="mt-2 text-xs text-danger"
    >
      错误：{{ currentState.error?.message ?? currentState.lastError }}
    </div>
  </AppCard>
</template>
