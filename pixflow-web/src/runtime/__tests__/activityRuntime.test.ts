import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ActivityFrame, ActivitySnapshot } from '@/api/activities'
import { useActivitiesStore } from '@/stores/activities'

const mocks = vi.hoisted(() => ({
  listActivities: vi.fn(),
  getStompConnection: vi.fn(),
  disposeStompConnection: vi.fn()
}))

vi.mock('@/api/activities', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/api/activities')>(),
  listActivities: mocks.listActivities
}))

vi.mock('@/transport/ws', () => ({
  getStompConnection: mocks.getStompConnection,
  disposeStompConnection: mocks.disposeStompConnection
}))

import { ActivityRuntime } from '@/runtime/activityRuntime'

describe('ActivityRuntime', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('replays frames received during reconnect after the REST snapshot', async () => {
    let onConnect: (() => void) | undefined
    let onFrame: ((frame: unknown) => void) | undefined
    const reconnectSnapshot = deferred<ActivitySnapshot>()
    mocks.listActivities
      .mockResolvedValueOnce(snapshot(4))
      .mockReturnValueOnce(reconnectSnapshot.promise)
    mocks.getStompConnection.mockImplementation((options: { onConnect?: () => void }) => {
      onConnect = options.onConnect
      return {
        subscribe: (_destination: string, handler: (frame: unknown) => void) => {
          onFrame = handler
          return { unsubscribe: vi.fn() }
        }
      }
    })

    const pinia = createPinia()
    setActivePinia(pinia)
    const runtime = new ActivityRuntime(pinia)
    await runtime.start()

    onConnect?.()
    onFrame?.(frame(6))
    reconnectSnapshot.resolve(snapshot(5))
    await vi.waitFor(() => expect(useActivitiesStore(pinia).cursor).toBe(6))

    expect(useActivitiesStore(pinia).items.get('activity-1')?.sequence).toBe(6)
  })

  it('disposes the application-scoped connection on logout', async () => {
    mocks.listActivities.mockResolvedValue(snapshot(0))
    mocks.getStompConnection.mockReturnValue({
      subscribe: () => ({ unsubscribe: vi.fn() })
    })
    const pinia = createPinia()
    const runtime = new ActivityRuntime(pinia)
    await runtime.start()

    runtime.stop()

    expect(mocks.disposeStompConnection).toHaveBeenCalledOnce()
  })

  it('keeps frames buffered until a failed reconnect snapshot succeeds', async () => {
    vi.useFakeTimers()
    let onConnect: (() => void) | undefined
    let onFrame: ((frame: unknown) => void) | undefined
    mocks.listActivities
      .mockResolvedValueOnce(snapshot(4))
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(snapshot(5))
    mocks.getStompConnection.mockImplementation((options: { onConnect?: () => void }) => {
      onConnect = options.onConnect
      return {
        subscribe: (_destination: string, handler: (frame: unknown) => void) => {
          onFrame = handler
          return { unsubscribe: vi.fn() }
        }
      }
    })
    const pinia = createPinia()
    const runtime = new ActivityRuntime(pinia)
    await runtime.start()

    onConnect?.()
    await vi.waitFor(() => expect(useActivitiesStore(pinia).error).not.toBeNull())
    onFrame?.(frame(6))
    expect(useActivitiesStore(pinia).cursor).toBe(4)

    await vi.advanceTimersByTimeAsync(2_000)
    await vi.waitFor(() => expect(useActivitiesStore(pinia).cursor).toBe(6))
    vi.useRealTimers()
  })
})

function snapshot(cursor: number): ActivitySnapshot {
  return { records: [], total: 0, page: 1, size: 50, cursor }
}

function frame(sequence: number): ActivityFrame {
  return {
    sequence,
    operation: 'UPSERT',
    activityId: 'activity-1',
    view: {
      activityId: 'activity-1',
      kind: 'PROCESS',
      status: 'RUNNING',
      progress: { completed: 1, total: 2, failed: 0 },
      conversationId: 'conversation-1',
      packageId: null,
      taskId: 'task-1',
      createdAt: '2026-07-21T00:00:00Z',
      startedAt: '2026-07-21T00:00:01Z',
      finishedAt: null,
      allowedActions: { cancel: true, retryFailed: false, clear: false },
      sequence
    }
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((fulfill) => { resolve = fulfill })
  return { promise, resolve }
}
