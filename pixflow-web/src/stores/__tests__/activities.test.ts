import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useActivitiesStore } from '@/stores/activities'
import type { ActivityView } from '@/api/activities'

describe('activity snapshot reconciliation', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('ignores frames at or behind the snapshot cursor', () => {
    const store = useActivitiesStore()
    store.replaceSnapshot({ records: [activity('activity-1', 10)], total: 1, page: 1, size: 50, cursor: 10 })

    store.applyFrame({ sequence: 9, operation: 'REMOVE', activityId: 'activity-1', view: null })
    store.applyFrame({ sequence: 10, operation: 'REMOVE', activityId: 'activity-1', view: null })

    expect(store.items.has('activity-1')).toBe(true)
    expect(store.cursor).toBe(10)
  })

  it('applies newer upserts and removals idempotently', () => {
    const store = useActivitiesStore()
    store.replaceSnapshot({ records: [], total: 0, page: 1, size: 50, cursor: 10 })

    store.applyFrame({ sequence: 11, operation: 'UPSERT', activityId: 'activity-1', view: activity('activity-1', 11) })
    store.applyFrame({ sequence: 11, operation: 'UPSERT', activityId: 'activity-1', view: activity('activity-1', 11) })
    store.applyFrame({ sequence: 12, operation: 'REMOVE', activityId: 'activity-1', view: null })

    expect(store.items.size).toBe(0)
    expect(store.cursor).toBe(12)
  })
})

function activity(activityId: string, sequence: number): ActivityView {
  return {
    activityId,
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
