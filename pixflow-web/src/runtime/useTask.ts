import { ref, type Ref } from 'vue'
import { getStompConnection, type StompConnection } from '@/transport/ws'
import { cancelTask, getTaskStatus, type TaskStatusView } from '@/api/tasks'
import type { ApiError } from '@/types/api'

/**
 * 任务运行时状态机。
 * 见 web.md §九。
 *
 *  - 初始快照：GET /api/tasks/{id}
 *  - 实时更新：WS 订阅 /topic/task-progress-{cid}-{tid}
 *  - 断线补偿：WS 重连后先 GET 拉一次最新，按 (updatedAt) 取较大者
 *  - 取消：乐观更新为 cancelled；WS 推送真 cancelled 帧幂等处理
 */

export type TaskPhase =
  | 'queued'
  | 'running'
  | 'completed'
  | 'failed'
  | 'partial'
  | 'cancelled'

export interface TaskState {
  taskId: string
  phase: TaskPhase
  progress: { done: number; total: number; failed: number; skipped: number }
  updatedAt: number
  /** 后端 taskType（V1 仅展示用） */
  taskType?: 'IMAGE_PROCESS' | 'IMAGE_GEN'
  /** 后端时间戳（用于断线补偿比较） */
  createdAt?: string
  startedAt?: string
  finishedAt?: string
  lastError?: string | null
  error?: ApiError
}

export interface TaskProgressFrame {
  taskId: string
  status?: TaskStatusView['status']
  done?: number
  total?: number
  failed?: number
  skipped?: number
  occurredAt?: string
}

function statusToPhase(s: TaskStatusView['status']): TaskPhase {
  switch (s) {
    case 'PENDING':
    case 'QUEUED': return 'queued'
    case 'RUNNING': return 'running'
    case 'COMPLETED': return 'completed'
    case 'FAILED': return 'failed'
    case 'CANCELLED': return 'cancelled'
    case 'PARTIAL': return 'partial'
  }
}

function parseTimestamp(s?: string): number {
  if (!s) return 0
  const t = Date.parse(s)
  return Number.isNaN(t) ? 0 : t
}

export function createTask(opts: { taskId: string; conversationId: string }) {
  const state: Ref<TaskState> = ref({
    taskId: opts.taskId,
    phase: 'queued',
    progress: { done: 0, total: 0, failed: 0, skipped: 0 },
    updatedAt: 0
  })
  const error: Ref<ApiError | null> = ref(null)
  let subscription: { unsubscribe: () => void } | null = null
  let stomp: StompConnection | null = null

  function applyBackend(s: TaskStatusView): void {
    const ts = Math.max(
      parseTimestamp(s.finishedAt),
      parseTimestamp(s.startedAt),
      parseTimestamp(s.createdAt)
    )
    if (ts <= state.value.updatedAt) return // ignore stale
    state.value = {
      ...state.value,
      phase: statusToPhase(s.status),
      progress: {
        done: s.progress?.done ?? 0,
        total: s.progress?.total ?? 0,
        failed: s.progress?.failed ?? 0,
        skipped: s.skipped ?? 0
      },
      taskType: s.taskType,
      createdAt: s.createdAt,
      startedAt: s.startedAt,
      finishedAt: s.finishedAt,
      lastError: s.lastError ?? null,
      updatedAt: ts || Date.now()
    }
  }

  function applyWsFrame(frame: TaskProgressFrame): void {
    if (frame.taskId !== opts.taskId) return
    const next: TaskState = { ...state.value }
    if (typeof frame.done === 'number') next.progress = { ...next.progress, done: frame.done }
    if (typeof frame.total === 'number') next.progress = { ...next.progress, total: frame.total }
    if (typeof frame.failed === 'number') next.progress = { ...next.progress, failed: frame.failed }
    if (typeof frame.skipped === 'number') next.progress = { ...next.progress, skipped: frame.skipped }
    if (frame.status) {
      // 取消帧幂等：若已 cancelled，不再变
      if (frame.status === 'CANCELLED' && state.value.phase === 'cancelled') return
      next.phase = statusToPhase(frame.status)
      if (['COMPLETED', 'FAILED', 'CANCELLED', 'PARTIAL'].includes(frame.status) && frame.occurredAt) {
        next.finishedAt = frame.occurredAt
      }
    }
    next.updatedAt = parseTimestamp(frame.occurredAt) || Date.now()
    state.value = next
  }

  async function refresh(): Promise<void> {
    try {
      const s = await getTaskStatus(opts.taskId)
      applyBackend(s)
    } catch (e) {
      const err = e as ApiError
      if (err.status === 404) {
        // 任务不存在
        state.value = { ...state.value, phase: 'failed', error: err }
      }
      error.value = err
    }
  }

  function subscribeWS(): void {
    if (subscription) return
    if (!stomp) {
      const wsUrl = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/progress`
      stomp = getStompConnection({ url: wsUrl })
    }
    const dest = `/topic/task-progress-${encodeURIComponent(opts.conversationId)}-${encodeURIComponent(opts.taskId)}`
    subscription = stomp.subscribe(dest, (msg: TaskProgressFrame) => applyWsFrame(msg))
  }

  function unsubscribeWS(): void {
    if (subscription) {
      subscription.unsubscribe()
      subscription = null
    }
  }

  async function cancel(): Promise<void> {
    // 乐观更新
    state.value = { ...state.value, phase: 'cancelled', updatedAt: Date.now() }
    try {
      await cancelTask(opts.conversationId, opts.taskId)
    } catch (e) {
      // 失败回退？V1 简单：保持 cancelled
      error.value = e as ApiError
    }
  }

  return {
    state,
    error,
    refresh,
    subscribeWS,
    unsubscribeWS,
    cancel
  }
}

export type Task = ReturnType<typeof createTask>
