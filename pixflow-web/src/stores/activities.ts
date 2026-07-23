import { computed, reactive, ref } from 'vue'
import { defineStore } from 'pinia'
import type { ActivityFrame, ActivitySnapshot, ActivityView } from '@/api/activities'

export const useActivitiesStore = defineStore('activities', () => {
  type ActivityCommand = 'cancel' | 'retry' | 'clear'
  const items = reactive(new Map<string, ActivityView>())
  const cursor = ref(0)
  const loading = ref(false)
  const error = ref<unknown>(null)
  let commandExecutor: ((activityId: string, command: ActivityCommand) => Promise<void>) | null = null

  const orderedItems = computed(() => [...items.values()].sort((left, right) => {
    return Date.parse(right.createdAt) - Date.parse(left.createdAt)
  }))

  function replaceSnapshot(snapshot: ActivitySnapshot): void {
    items.clear()
    for (const activity of snapshot.records) items.set(activity.activityId, activity)
    cursor.value = snapshot.cursor
  }

  function applyFrame(frame: ActivityFrame): void {
    // REST snapshot 是重连后的权威基线，旧帧不能覆盖或删除更新的投影。
    if (frame.sequence <= cursor.value) return
    if (frame.operation === 'REMOVE') {
      items.delete(frame.activityId)
    } else if (frame.view !== null) {
      items.set(frame.activityId, frame.view)
    }
    cursor.value = frame.sequence
  }

  function clear(): void {
    items.clear()
    cursor.value = 0
    loading.value = false
    error.value = null
  }

  function setCommandExecutor(executor: typeof commandExecutor): void {
    commandExecutor = executor
  }

  async function runCommand(activityId: string, command: ActivityCommand): Promise<void> {
    if (!commandExecutor) throw new Error('Activity runtime 尚未启动')
    await commandExecutor(activityId, command)
  }

  return { items, cursor, loading, error, orderedItems, replaceSnapshot, applyFrame, clear, setCommandExecutor, runCommand }
})
