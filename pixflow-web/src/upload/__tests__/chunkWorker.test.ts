import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/types/api'
import { runChunkPool } from '@/upload/chunkWorker'

const file = new File([new Uint8Array(10)], 'demo.zip')

describe('runChunkPool', () => {
  it('uploads only missing indices and counts the short final chunk exactly', async () => {
    const calls: number[] = []
    const progress: number[] = []
    const result = await runChunkPool({
      uploadId: 'u1', file, chunkSize: 3, expectedChunks: 4, initialUploadedIndices: [0, 2],
      concurrency: 2, signal: new AbortController().signal,
      hashChunk: () => Promise.resolve('a'.repeat(64)),
      putChunk: (_uploadId, index) => {
        calls.push(index)
        return Promise.resolve({ uploadId: 'u1', index, status: index === 1 ? 'ALREADY_EXISTS' : 'ACCEPTED', uploadedChunks: [0, 1, 2, index] })
      },
      onProgress: ({ uploadedBytes }) => progress.push(uploadedBytes)
    })

    expect(calls.sort()).toEqual([1, 3])
    expect([...result.uploadedIndices].sort()).toEqual([0, 1, 2, 3])
    expect(progress.at(-1)).toBe(10)
  })

  it('never exceeds configured concurrency', async () => {
    let active = 0
    let maximum = 0
    await runChunkPool({
      uploadId: 'u1', file, chunkSize: 2, expectedChunks: 5, initialUploadedIndices: [],
      concurrency: 2, signal: new AbortController().signal,
      hashChunk: () => Promise.resolve('a'.repeat(64)),
      putChunk: async (_uploadId, index) => {
        active++
        maximum = Math.max(maximum, active)
        await Promise.resolve()
        active--
        return { uploadId: 'u1', index, status: 'ACCEPTED', uploadedChunks: [index] }
      }
    })
    expect(maximum).toBe(2)
  })

  it('retries 5xx but not 429 or 409', async () => {
    const serverAttempts = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'INTERNAL_ERROR'))
      .mockResolvedValue({ uploadId: 'u1', index: 0, status: 'ACCEPTED', uploadedChunks: [0] })
    const serverResult = await runChunkPool(baseOptions(serverAttempts))
    expect(serverAttempts).toHaveBeenCalledTimes(2)
    expect(serverResult.failedIndices).toHaveLength(0)

    for (const [status, code] of [[429, 'UPLOAD_RATE_LIMITED'], [409, 'UPLOAD_SESSION_NOT_UPLOADING']] as const) {
      const request = vi.fn().mockRejectedValue(apiError(status, code))
      const result = await runChunkPool(baseOptions(request))
      expect(request).toHaveBeenCalledTimes(1)
      expect(result.failedIndices.get(0)?.errorCode).toBe(code)
    }
  })

  it('retries transport failures and stops after five attempts', async () => {
    const recovered = vi.fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValue({ uploadId: 'u1', index: 0, status: 'ACCEPTED', uploadedChunks: [0] })
    const recoveredResult = await runChunkPool(baseOptions(recovered))
    expect(recovered).toHaveBeenCalledTimes(2)
    expect(recoveredResult.failedIndices.size).toBe(0)

    const exhausted = vi.fn().mockRejectedValue(new Error('offline'))
    const exhaustedResult = await runChunkPool(baseOptions(exhausted))
    expect(exhausted).toHaveBeenCalledTimes(5)
    expect(exhaustedResult.failedIndices.get(0)?.errorCode).toBe('NETWORK_ERROR')
  })

  it('passes the AbortSignal into an in-flight PUT', async () => {
    const controller = new AbortController()
    let signalSeen: AbortSignal | undefined
    let started!: () => void
    const requestStarted = new Promise<void>((resolve) => { started = resolve })
    const request = vi.fn((_uploadId: string, _index: number, _blob: Blob, _hash: string, signal?: AbortSignal) => {
      signalSeen = signal
      started()
      return new Promise((_resolve, reject) => {
        signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true })
      })
    })

    const running = runChunkPool({ ...baseOptions(request), signal: controller.signal })
    await requestStarted
    controller.abort()
    const result = await running

    expect(signalSeen).toBe(controller.signal)
    expect(result.aborted).toBe(true)
    expect(result.failedIndices.size).toBe(0)
  })

  it('does not schedule work after abort', async () => {
    const controller = new AbortController()
    const request = vi.fn((_uploadId: string, index: number) => {
      controller.abort()
      return Promise.resolve({ uploadId: 'u1', index, status: 'ACCEPTED' as const, uploadedChunks: [index] })
    })
    const result = await runChunkPool({ ...baseOptions(request), expectedChunks: 4, signal: controller.signal })
    expect(request).toHaveBeenCalledTimes(1)
    expect(result.aborted).toBe(true)
  })
})

function baseOptions(putChunk: ReturnType<typeof vi.fn>) {
  return {
    uploadId: 'u1', file, chunkSize: 10, expectedChunks: 1, initialUploadedIndices: [],
    concurrency: 1, signal: new AbortController().signal,
    hashChunk: () => Promise.resolve('a'.repeat(64)), putChunk,
    wait: () => Promise.resolve()
  }
}

function apiError(status: number, errorCode: string): ApiError {
  return new ApiError({ status, errorCode, message: errorCode, traceId: '' })
}
