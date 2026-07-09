import { afterEach, describe, expect, it, vi } from 'vitest'
import { listConversations } from '@/api/conversations'

function jsonResponse(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status: 200,
    headers: { 'content-type': 'application/json' }
  })
}

describe('conversation contract adapter', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('uses includeArchived/page=1 and maps backend id to conversationId', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) =>
      jsonResponse({
        records: [{ id: 'c1', title: '会话', packageId: 2, updatedAt: '2026-07-06T10:00:00Z' }],
        total: 1,
        page: 1,
        size: 50
      })
    )
    vi.stubGlobal('fetch', fetchMock)

    const page = await listConversations({ includeArchived: false, page: 1, size: 50 })

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/conversations?includeArchived=false&page=1&size=50')
    expect(page.items[0]).toMatchObject({ conversationId: 'c1', packageId: '2' })
  })
})
