<script setup lang="ts">
import { computed, onUnmounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useTask, type Task } from '@/runtime/useTask'

const props = defineProps<{
  taskId: string
  conversationId: string
}>()

const task: Task = useTask({ taskId: props.taskId, conversationId: props.conversationId })

const status = computed(() => task.state.value.phase)
const progress = computed(() => task.state.value.progress)
const percent = computed(() => {
  const t = progress.value.total
  if (t <= 0) return 0
  return Math.min(100, Math.round((progress.value.done / t) * 100))
})

watch(() => props.taskId, async () => {
  task.refresh()
  task.subscribeWS()
}, { immediate: true })

onUnmounted(() => {
  task.unsubscribeWS()
})

async function handleCancel(): Promise<void> {
  try {
    await ElMessageBox.confirm('确认取消该任务？', '取消任务', {
      type: 'warning'
    })
    await task.cancel()
    ElMessage.success('已取消')
  } catch {
    // 用户取消弹窗
  }
}

defineExpose({ task })
</script>

<template>
  <div class="task-card" :data-status="status">
    <header class="task-card-head">
      <span class="task-id">task: {{ taskId.slice(0, 8) }}</span>
      <span :class="['status', status]">{{ status }}</span>
    </header>
    <el-progress :percentage="percent" :status="status === 'failed' ? 'exception' : status === 'completed' ? 'success' : undefined" />
    <div class="task-card-stats">
      <span>done: {{ progress.done }}</span>
      <span>total: {{ progress.total }}</span>
      <span v-if="progress.failed > 0" class="failed">failed: {{ progress.failed }}</span>
    </div>
    <div class="task-card-actions">
      <el-button v-if="status === 'running' || status === 'queued'" size="small" @click="handleCancel">取消</el-button>
      <el-button v-else size="small" @click="task.refresh()">刷新</el-button>
    </div>
  </div>
</template>

<style scoped>
.task-card {
  border: 1px solid var(--color-border);
  border-radius: 6px;
  padding: 12px;
  background: var(--color-bg);
  margin-bottom: 8px;
}
.task-card-head { display: flex; justify-content: space-between; margin-bottom: 8px; }
.task-id { font-family: monospace; font-size: 12px; color: var(--color-text-mute); }
.status {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  text-transform: uppercase;
}
.status.queued, .status.running { background: #ecf5ff; color: var(--color-primary); }
.status.completed, .status.partial { background: #f0f9eb; color: var(--color-success); }
.status.failed { background: #fef0f0; color: var(--color-error); }
.status.cancelled { background: #f4f4f5; color: var(--color-text-mute); }
.task-card-stats { display: flex; gap: 12px; font-size: 12px; color: var(--color-text-mute); margin: 6px 0; }
.failed { color: var(--color-error); }
.task-card-actions { display: flex; gap: 8px; justify-content: flex-end; }
</style>