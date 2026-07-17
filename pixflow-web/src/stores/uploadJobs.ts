import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'
import { createUploadJob, type UploadJobHandle } from '@/upload/uploadJob'
import type { ApiError } from '@/types/api'
import type { UploadJobState } from '@/types/upload'

/**
 * 上传任务管理。
 *
 * 行为：
 * - Map<jobId, UploadJobState> 是事实源（响应式）；handle 引用单独保存以便 cancel/retry
 * - activeJobs 保留 paused/error，确保用户始终能看到继续与重试入口
 *
 * 注：handles 用 shallowRef 以避免 Pinia 深度 unwrap Ref<UploadJobState>
 */
export const useUploadJobsStore = defineStore('uploadJobs', () => {
  const items = ref<Map<string, UploadJobState>>(new Map())
  const handles = shallowRef<Map<string, UploadJobHandle>>(new Map())

  const activeJobs = computed(() => {
    const arr: UploadJobState[] = []
    for (const j of items.value.values()) {
      if (j.phase !== 'done' && j.phase !== 'cancelled') {
        arr.push(j)
      }
    }
    return arr
  })

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
    handles.value.delete(jobId)
  }

  /**
   * 创建并启动整文件上传。
   * 失败时更新 phase=error；不抛出取消（取消是预期流）。
   */
  async function startWholeFile(file: File): Promise<void> {
    const handle = createUploadJob({
      file,
      onProgress: (p) => {
        // handle 内状态机拥有完整快照；Pinia 每次复制全量，避免 hash/uploadId/分片数漂移。
        items.value.set(handle.state.value.jobId, { ...handle.state.value, phase: p.phase })
      },
      onError: (err: ApiError) => {
        items.value.set(handle.state.value.jobId, { ...handle.state.value, error: err, phase: 'error' })
      },
      onDedup: () => {
        // 后续可在此向 toast 推"已存在/正在上传"
      },
      onDone: () => {
        items.value.set(handle.state.value.jobId, { ...handle.state.value, phase: 'done' })
      }
    })
    add(handle.state.value)
    handles.value.set(handle.state.value.jobId, handle)
    await handle.run()
  }

  async function cancel(jobId: string): Promise<void> {
    const h = handles.value.get(jobId)
    if (!h) return
    await h.cancel()
  }

  function pause(jobId: string): void {
    handles.value.get(jobId)?.pause()
  }

  async function resume(jobId: string): Promise<void> {
    await handles.value.get(jobId)?.resume()
  }

  async function retry(jobId: string): Promise<void> {
    const h = handles.value.get(jobId)
    if (!h) return
    await h.retry()
  }

  return { items, handles, activeJobs, add, update, get, remove, startWholeFile, pause, resume, cancel, retry }
})
