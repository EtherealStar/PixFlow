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
  createdAt: string
  startedAt?: string
  finishedAt?: string
}

export interface TaskResult {
  resultId: string
  taskId: string
  status: 'SUCCESS' | 'FAILED' | 'SKIPPED'
  outputPath?: string
  errorMsg?: string
  createdAt: string
}

export interface DownloadHandle {
  url: string
  expiresAt: string
}

export function getTaskStatus(taskId: string): Promise<TaskStatusView> {
  return request<TaskStatusView>(`/api/tasks/${encodeURIComponent(taskId)}`)
}

export function listConversationTasks(conversationId: string, params: { page?: number; size?: number } = {}): Promise<Page<TaskStatusView>> {
  const q = new URLSearchParams()
  if (params.page !== undefined) q.set('page', String(params.page))
  if (params.size !== undefined) q.set('size', String(params.size))
  const qs = q.toString()
  return request<Page<TaskStatusView>>(
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

export function getResultDownload(taskId: string, resultId: string): Promise<DownloadHandle> {
  return request<DownloadHandle>(
    `/api/tasks/${encodeURIComponent(taskId)}/downloads?resultId=${encodeURIComponent(resultId)}`
  )
}

export function getBundleDownload(taskId: string): Promise<DownloadHandle> {
  return request<DownloadHandle>(`/api/tasks/${encodeURIComponent(taskId)}/downloads/bundle`)
}

export function cancelTask(conversationId: string, taskId: string): Promise<{ cancelled: true }> {
  return request<{ cancelled: true }>(
    `/api/conversations/${encodeURIComponent(conversationId)}/tasks/${encodeURIComponent(taskId)}/cancel`,
    { method: 'POST', noRetry: true }
  )
}