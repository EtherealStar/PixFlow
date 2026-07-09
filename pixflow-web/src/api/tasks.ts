import { request } from './client'
import type { Page } from '@/types/api'

/**
 * 任务查询 / 结果 / 下载。
 * 见 api.md `Task API`。
 */

export type TaskStatus =
  | 'PENDING'
  | 'QUEUED'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'PARTIAL'

export interface TaskStatusView {
  taskId: string
  status: TaskStatus
  taskType: 'IMAGE_PROCESS' | 'IMAGE_GEN'
  progress: { done: number; total: number; failed: number }
  skipped: number
  lastError?: string | null
  createdAt: string
  startedAt?: string
  finishedAt?: string
}

export interface TaskSummary {
  taskId: string
  taskType: 'IMAGE_PROCESS' | 'IMAGE_GEN'
  status: TaskStatus
  done: number
  total: number
  failed: number
  createdAt: string
  finishedAt?: string | null
}

export interface TaskResult {
  resultId: string
  taskId: string
  conversationId?: string
  status: 'SUCCESS' | 'FAILED' | 'SKIPPED'
  kind?: 'BRANCH' | 'GROUP' | 'GENERATIVE'
  imageId?: string
  skuId?: string
  groupKey?: string
  viewId?: string
  branchId?: string
  filename?: string
  displayName?: string | null
  size?: number | null
  url?: string | null
  errorMsg?: string
  createdAt: string
  finishedAt?: string
}

export interface DownloadHandle {
  url: string
  expiresAt: string
  contentType?: string
  sizeBytes?: number | null
}

export interface CancellationResult {
  taskId: string
  cancelled: boolean
}

export function getTaskStatus(taskId: string): Promise<TaskStatusView> {
  return request<TaskStatusView>(`/api/tasks/${encodeURIComponent(taskId)}`)
}

export function listConversationTasks(conversationId: string, params: { page?: number; size?: number } = {}): Promise<Page<TaskSummary>> {
  const q = new URLSearchParams()
  if (params.page !== undefined) q.set('page', String(params.page))
  if (params.size !== undefined) q.set('size', String(params.size))
  const qs = q.toString()
  return request<Page<TaskSummary>>(
    `/api/conversations/${encodeURIComponent(conversationId)}/tasks${qs ? `?${qs}` : ''}`
  )
}

export function listTaskResults(taskId: string, params: { page?: number; size?: number } = {}): Promise<Page<TaskResult>> {
  const q = new URLSearchParams()
  if (params.page !== undefined) q.set('page', String(params.page))
  if (params.size !== undefined) q.set('size', String(params.size))
  const qs = q.toString()
  return request<Page<TaskResult>>(`/api/tasks/${encodeURIComponent(taskId)}/results${qs ? `?${qs}` : ''}`)
}

export function listConversationImages(conversationId: string, params: { page?: number; size?: number } = {}): Promise<Page<TaskResult>> {
  const q = new URLSearchParams()
  if (params.page !== undefined) q.set('page', String(params.page))
  if (params.size !== undefined) q.set('size', String(params.size))
  const qs = q.toString()
  return request<Page<TaskResult>>(
    `/api/conversations/${encodeURIComponent(conversationId)}/images${qs ? `?${qs}` : ''}`
  )
}

export function getResultDownload(taskId: string, resultId: string): Promise<DownloadHandle> {
  return request<DownloadHandle>(
    `/api/tasks/${encodeURIComponent(taskId)}/downloads?resultId=${encodeURIComponent(resultId)}`
  )
}

export function getBundleDownload(taskId: string): Promise<DownloadHandle> {
  return request<DownloadHandle>(`/api/tasks/${encodeURIComponent(taskId)}/downloads/bundle`)
}

export function cancelTask(conversationId: string, taskId: string): Promise<CancellationResult> {
  return request<CancellationResult>(
    `/api/conversations/${encodeURIComponent(conversationId)}/tasks/${encodeURIComponent(taskId)}/cancel`,
    { method: 'POST', noRetry: true }
  )
}

export function deleteTaskResult(taskId: string, resultId: string): Promise<void> {
  return request<void>(`/api/tasks/${encodeURIComponent(taskId)}/results/${encodeURIComponent(resultId)}`, {
    method: 'DELETE',
    noRetry: true
  })
}

export function renameTaskResult(taskId: string, resultId: string, displayName: string): Promise<TaskResult> {
  return request<TaskResult>(`/api/tasks/${encodeURIComponent(taskId)}/results/${encodeURIComponent(resultId)}`, {
    method: 'PATCH',
    body: { displayName },
    noRetry: true
  })
}
