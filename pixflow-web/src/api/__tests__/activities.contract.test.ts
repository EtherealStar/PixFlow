import { beforeEach, describe, expect, it, vi } from 'vitest'
import { cancelActivity, clearActivity, listActivities, retryFailedActivity } from '@/api/activities'

describe('activity API contract', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  it('loads the 1-based snapshot with its cursor', async () => {
    fetchMock.mockResolvedValue(jsonResponse({
      records: [activity('activity-1', 41)],
      total: 1,
      page: 1,
      size: 50,
      cursor: 41
    }))

    const snapshot = await listActivities({ page: 1, size: 50, status: 'RUNNING' })

    expect(snapshot.records[0]?.activityId).toBe('activity-1')
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/activities?page=1&size=50&status=RUNNING')
  })

  it('addresses every command only by opaque activity identity', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(null))
      .mockResolvedValueOnce(jsonResponse({
        sourceActivityId: 'activity-1',
        activityId: 'activity-2',
        taskId: 'task-2',
        retryOfTaskId: 'task-1'
      }))
      .mockResolvedValueOnce(jsonResponse(null))

    await cancelActivity('activity-1')
    await retryFailedActivity('activity-1')
    await clearActivity('activity-1')

    expect(fetchMock.mock.calls.map(([url, init]) => [url, (init as RequestInit).method])).toEqual([
      ['/api/activities/activity-1/cancel', 'POST'],
      ['/api/activities/activity-1/retry-failed', 'POST'],
      ['/api/activities/activity-1', 'DELETE']
    ])
    for (const [, init] of fetchMock.mock.calls as Array<[string, RequestInit]>) {
      expect(init.body).toBeUndefined()
      expect(init.headers).not.toHaveProperty('Idempotency-Key')
    }
  })
})

function jsonResponse(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, code: 'OK', data }), {
    status: 200,
    headers: { 'content-type': 'application/json' }
  })
}

function activity(activityId: string, sequence: number) {
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
