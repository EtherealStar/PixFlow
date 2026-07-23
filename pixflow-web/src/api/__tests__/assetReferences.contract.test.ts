import { beforeEach, describe, expect, it, vi } from 'vitest'
import { searchAssetReferences } from '@/api/assetReferences'

describe('asset reference API contract', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  it('searches canonical candidates and sends exclusions without parsing keys', async () => {
    fetchMock.mockResolvedValueOnce(response({
      records: [{
        referenceKey: 'package:7/image:9',
        kind: 'IMAGE',
        sourceType: 'ORIGINAL',
        displayPath: 'summer.zip / SKU-1 / front.png',
        hasChildren: false,
        sourceGroup: 'MATERIALS'
      }],
      total: 1,
      page: 1,
      size: 20
    }))

    await expect(searchAssetReferences('front', ['package:8'])).resolves.toHaveLength(1)
    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      '/api/asset-references?page=1&size=20&query=front&excludeReferenceKey=package%3A8'
    )
  })

  it('rejects backend fields outside the public candidate allowlist', async () => {
    fetchMock.mockResolvedValueOnce(response({
      records: [{
        referenceKey: 'package:7', kind: 'PACKAGE', sourceType: null,
        displayPath: 'summer.zip', hasChildren: true, sourceGroup: 'MATERIALS', objectKey: 'secret'
      }],
      total: 1, page: 1, size: 20
    }))

    await expect(searchAssetReferences('', [])).rejects.toBeDefined()
  })
})

function response(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, code: 'OK', data }), {
    status: 200,
    headers: { 'content-type': 'application/json' }
  })
}
