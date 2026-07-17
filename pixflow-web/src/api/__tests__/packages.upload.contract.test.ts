import { beforeEach, describe, expect, it, vi } from 'vitest'
import { completeUpload, deleteSession, getSession, initUpload, putChunk } from '@/api/packages'

describe('package upload API contract', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    fetchMock.mockReset()
    vi.stubGlobal('fetch', fetchMock)
  })

  it.each([
    { mode: 'UPLOAD', uploadId: 'new-id', packageId: null, status: null, chunkSize: 5, expectedChunks: 2, uploadedChunks: [] },
    { mode: 'RESUME', uploadId: 'old-id', packageId: null, status: 'UPLOADING', chunkSize: 5, expectedChunks: 2, uploadedChunks: [0] },
    { mode: 'DEDUP', uploadId: null, packageId: 42, status: 'READY', chunkSize: 0, expectedChunks: 0, uploadedChunks: [] }
  ] as const)('preserves init mode $mode', async (payload) => {
    respond(payload)
    await expect(initUpload({ filename: 'a.zip', size: 6, fileHash: 'a'.repeat(64), chunkSize: 5 })).resolves.toEqual(payload)
  })

  it('preserves the nine-field session snapshot', async () => {
    const payload = {
      uploadId: 'u1', fileHash: 'b'.repeat(64), size: 6, chunkSize: 5, expectedChunks: 2,
      uploadedChunks: [0], failedChunks: [], status: 'UPLOADING', packageId: null
    }
    respond(payload)
    await expect(getSession('u1')).resolves.toEqual(payload)
  })

  it.each(['ACCEPTED', 'ALREADY_EXISTS'] as const)('treats chunk status %s as success', async (status) => {
    respond({ uploadId: 'u1', index: 1, status, uploadedChunks: [0, 1] })
    const controller = new AbortController()
    await expect(putChunk('u1', 1, new Blob(['x']), 'c'.repeat(64), controller.signal)).resolves.toMatchObject({ status })
    const init = fetchMock.mock.calls.at(-1)?.[1] as RequestInit
    expect(init.signal).toBe(controller.signal)
  })

  it('preserves complete and cancel responses', async () => {
    respond({ packageId: 9, status: 'UPLOADED' })
    await expect(completeUpload('u1', 'd'.repeat(64))).resolves.toEqual({ packageId: 9, status: 'UPLOADED' })
    respond({ uploadId: 'u1', status: 'CANCELLED' })
    await expect(deleteSession('u1')).resolves.toEqual({ uploadId: 'u1', status: 'CANCELLED' })
  })

  function respond(data: unknown): void {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ success: true, code: 'OK', data }), {
      status: 200,
      headers: { 'content-type': 'application/json' }
    }))
  }
})
