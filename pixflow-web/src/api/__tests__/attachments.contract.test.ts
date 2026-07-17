import { afterEach, describe, expect, it, vi } from 'vitest'
import { uploadAttachment } from '@/api/attachments'

function jsonResponse(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status: 200,
    headers: { 'content-type': 'application/json' }
  })
}

describe('attachment upload compatibility adapter', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('reuses package upload endpoint and returns top-level package binding payload', async () => {
    const fetchMock = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
      jsonResponse({ packageId: 123, status: 'UPLOADED', messageConfirmed: true })
    )
    vi.stubGlobal('fetch', fetchMock)

    const file = new File(['zip-bytes'], 'assets.zip', { type: 'application/zip' })
    const result = await uploadAttachment('conversation-1', file)

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/files/packages')
    expect(fetchMock.mock.calls[0]?.[0]).not.toContain('/attachments')
    expect(result).toMatchObject({
      packageId: '123',
      metadata: {
        filename: 'assets.zip',
        contentType: 'application/zip'
      },
      status: 'UPLOADED'
    })
    expect(result).not.toHaveProperty('attachment')
  })
})
