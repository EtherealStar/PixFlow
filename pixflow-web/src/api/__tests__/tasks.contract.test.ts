import { beforeEach, describe, expect, it, vi } from 'vitest'
import { retryFailedTask } from '@/api/tasks'

describe('task API contract', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  it('retries failed units with only the idempotency header', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({
      success: true,
      code: 'OK',
      data: {
        taskId: '101',
        retryOfTaskId: '99',
        selectedUnitCount: 2,
        status: 'QUEUED'
      }
    }), { status: 200, headers: { 'content-type': 'application/json' } }))

    const response = await retryFailedTask('99', 'request-key')

    expect(response.taskId).toBe('101')
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/tasks/99/retry-failed')
    expect(init.method).toBe('POST')
    expect(init.headers).toMatchObject({ 'Idempotency-Key': 'request-key' })
    expect(init.body).toBeUndefined()
  })
})
