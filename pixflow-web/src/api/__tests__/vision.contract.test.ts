import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getVisualFacts, reanalyzeVisualFacts, replaceVisualFacts } from '@/api/vision'

describe('vision API contract', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  it('accepts an active analysis without a current facts document', async () => {
    fetchMock.mockResolvedValue(jsonResponse(view({ facts: null, writer: null })))

    await expect(getVisualFacts(7, 'SKU / 1')).resolves.toMatchObject({
      analysisStatus: 'RUNNING',
      facts: null,
      writer: null
    })
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/vision/packages/7/skus/SKU%20%2F%201/facts')
  })

  it('sends only the versioned replacement and reanalysis commands', async () => {
    const facts = emptyFacts()
    fetchMock
      .mockResolvedValueOnce(jsonResponse(view({ facts, writer: 'ADMINISTRATOR_EDITED' })))
      .mockResolvedValueOnce(jsonResponse(view({ facts, writer: 'ADMINISTRATOR_EDITED' })))

    await replaceVisualFacts(7, 'SKU-1', 3, facts)
    await reanalyzeVisualFacts(7, 'SKU-1', 4, 'request-1')

    expect(JSON.parse((fetchMock.mock.calls[0]?.[1] as RequestInit).body as string)).toEqual({
      expectedVersion: 3,
      facts
    })
    expect(JSON.parse((fetchMock.mock.calls[1]?.[1] as RequestInit).body as string)).toEqual({
      expectedGeneration: 4,
      requestId: 'request-1'
    })
  })

  it('rejects owner-only fields at the adapter boundary', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ...view(), providerPrompt: 'secret' }))

    await expect(getVisualFacts(7, 'SKU-1')).rejects.toMatchObject({ name: 'ZodError' })
  })
})

function jsonResponse(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, code: 'OK', data }), {
    status: 200,
    headers: { 'content-type': 'application/json' }
  })
}

function emptyFacts() {
  return {
    common: {
      categoryAppearance: '',
      dominantColors: [],
      visibleMaterials: [],
      shapes: [],
      visibleComponents: [],
      patterns: [],
      visibleText: [],
      background: '',
      viewTypes: []
    },
    attributes: [],
    limitations: [],
    conflicts: []
  }
}

function view(overrides: Record<string, unknown> = {}) {
  return {
    packageId: 7,
    skuId: 'SKU-1',
    analysisStatus: 'RUNNING',
    analysisGeneration: 4,
    facts: emptyFacts(),
    version: 3,
    writer: 'AI_GENERATED',
    updatedAt: '2026-07-21T00:00:00Z',
    failureCode: null,
    ...overrides
  }
}
