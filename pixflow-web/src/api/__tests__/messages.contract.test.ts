import { afterEach, describe, expect, it, vi } from 'vitest'
import { getHistory } from '@/api/messages'

function jsonResponse(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status: 200,
    headers: { 'content-type': 'application/json' }
  })
}

describe('message history contract adapter', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('requests page response without sinceSeq and parses metadata JSON strings', async () => {
    const fetchMock = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
      jsonResponse({
        records: [{
          id: 'm1',
          seq: 1,
          role: 'USER',
          content: 'hello',
          metadata: '{"source":"test"}',
          createdAt: '2026-07-06T10:00:00Z',
          compactionBoundary: true,
          compactionMarker: 'SUMMARY',
          attachedPackageId: 123
        }],
        total: 1,
        page: 1,
        size: 50
      })
    )
    vi.stubGlobal('fetch', fetchMock)

    const page = await getHistory('c1', { size: 50 })

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/conversations/c1/messages?page=1&size=50')
    expect(page.items[0]).toMatchObject({
      metadata: { source: 'test' },
      isCompactionBoundary: true,
      isCompactionSummary: true,
      attachedPackageId: '123'
    })
  })
})
