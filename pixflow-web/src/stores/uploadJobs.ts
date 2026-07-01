import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { UploadJobState } from '@/types/upload'

export const useUploadJobsStore = defineStore('uploadJobs', () => {
  const items = ref<Map<string, UploadJobState>>(new Map())

  function add(state: UploadJobState): void {
    items.value.set(state.jobId, state)
  }

  function update(jobId: string, patch: Partial<UploadJobState>): void {
    const cur = items.value.get(jobId)
    if (!cur) return
    items.value.set(jobId, { ...cur, ...patch })
  }

  function get(jobId: string): UploadJobState | undefined {
    return items.value.get(jobId)
  }

  function remove(jobId: string): void {
    items.value.delete(jobId)
  }

  return { items, add, update, get, remove }
})