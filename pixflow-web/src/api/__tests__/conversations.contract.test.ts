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

  it('uses the one-way conversationId/page contract', async () => {
    const fetchMock = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
      jsonResponse({
        records: [{
          conversationId: 'c1',
          title: '会话',
          createdAt: '2026-07-06T09:00:00Z',
          updatedAt: '2026-07-06T10:00:00Z'
        }],
        total: 1,
        page: 1,
        size: 50
      })
    )
    vi.stubGlobal('fetch', fetchMock)

    const page = await listConversations({ page: 1, size: 50 })

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/conversations?page=1&size=50')
    expect(page.records[0]).toMatchObject({ conversationId: 'c1', title: '会话' })
  })

  it('rejects legacy conversation fields instead of normalizing them', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      records: [{
        conversationId: 'c1',
        title: '会话',
        createdAt: '2026-07-06T09:00:00Z',
        updatedAt: '2026-07-06T10:00:00Z',
        archived: false
      }],
      total: 1,
      page: 1,
      size: 50
    })))

    await expect(listConversations()).rejects.toMatchObject({ name: 'ZodError' })
  })
})
