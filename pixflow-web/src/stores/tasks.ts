import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { TaskState } from '@/runtime/useTask'

/**
 * 任务订阅集合。
 * 不存 WS 连接本身（连接在 `transport/ws.ts` 共享单例）。
 */
export const useTasksStore = defineStore('tasks', () => {
  const items = ref<Map<string, TaskState>>(new Map())
  const watching = ref<Set<string>>(new Set())

  function watch(taskId: string): void {
    watching.value.add(taskId)
  }
  function unwatch(taskId: string): void {
    watching.value.delete(taskId)
  }
  function isWatching(taskId: string): boolean {
    return watching.value.has(taskId)
  }

  function upsert(state: TaskState): void {
    items.value.set(state.taskId, state)
  }

  function get(taskId: string): TaskState | undefined {
    return items.value.get(taskId)
  }

  function addQueued(taskId: string, retryOfTaskId?: string): TaskState {
    const state: TaskState = {
      taskId,
      phase: 'queued',
      progress: { done: 0, total: 0, failed: 0, skipped: 0 },
      retryOfTaskId,
      updatedAt: Date.now()
    }
    upsert(state)
    return state
  }

  return { items, watching, watch, unwatch, isWatching, upsert, get, addQueued }
})
