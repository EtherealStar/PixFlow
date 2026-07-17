import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/types/api'
import { createUploadJob, type UploadJobDependencies } from '@/upload/uploadJob'
import type { InitUploadResponse, UploadSessionState } from '@/types/upload'
import type { ChunkWorkerResult } from '@/upload/chunkWorker'

const fileHash = 'a'.repeat(64)
const file = new File([new Uint8Array(10)], 'demo.zip')

describe('createUploadJob', () => {
  beforeEach(() => localStorage.clear())

  it('runs a new upload through complete', async () => {
    const dependencies = successfulDependencies()
    const job = createUploadJob({ file, dependencies })

    await job.run()

    expect(job.state.value.phase).toBe('done')
    expect(dependencies.runChunks).toHaveBeenCalledWith(expect.objectContaining({
      uploadId: 'u1', expectedChunks: 2, initialUploadedIndices: []
    }))
    expect(dependencies.init).toHaveBeenCalledWith(expect.any(Object), expect.any(AbortSignal))
    expect(dependencies.getSession).toHaveBeenCalledWith('u1', expect.any(AbortSignal))
    expect(dependencies.complete).toHaveBeenCalledWith('u1', fileHash, expect.any(AbortSignal))
    expect(dependencies.complete).toHaveBeenCalledTimes(1)
  })

  it('uses the RESUME server snapshot as the missing-chunk fact source', async () => {
    const dependencies = successfulDependencies({
      init: vi.fn<UploadJobDependencies['init']>().mockResolvedValue({
        mode: 'RESUME', uploadId: 'u1', packageId: null, status: 'UPLOADING',
        chunkSize: 5, expectedChunks: 4, uploadedChunks: [0]
      }),
      getSession: vi.fn<UploadJobDependencies['getSession']>()
        .mockResolvedValue(session({ expectedChunks: 4, uploadedChunks: [0, 2] }))
    })
    const job = createUploadJob({ file, dependencies })

    await job.run()

    expect(dependencies.runChunks).toHaveBeenCalledWith(expect.objectContaining({ initialUploadedIndices: [0, 2] }))
  })

  it('finishes DEDUP without GET, PUT, or complete', async () => {
    const dependencies = successfulDependencies({
      init: vi.fn<UploadJobDependencies['init']>().mockResolvedValue({
        mode: 'DEDUP', uploadId: null, packageId: 42, status: 'READY',
        chunkSize: 0, expectedChunks: 0, uploadedChunks: []
      })
    })
    const onDone = vi.fn()
    const job = createUploadJob({ file, dependencies, onDone })

    await job.run()

    expect(job.state.value.phase).toBe('done')
    expect(dependencies.getSession).not.toHaveBeenCalled()
    expect(dependencies.runChunks).not.toHaveBeenCalled()
    expect(dependencies.complete).not.toHaveBeenCalled()
    expect(onDone).toHaveBeenCalledWith(42)
  })

  it('accepts GET READY and re-inits an expired snapshot only once', async () => {
    const readyDependencies = successfulDependencies({
      getSession: vi.fn<UploadJobDependencies['getSession']>()
        .mockResolvedValue(session({ status: 'READY', packageId: 7, uploadedChunks: [0, 1] }))
    })
    const readyJob = createUploadJob({ file, dependencies: readyDependencies })
    await readyJob.run()
    expect(readyJob.state.value.phase).toBe('done')
    expect(readyDependencies.runChunks).not.toHaveBeenCalled()

    const init = vi.fn<UploadJobDependencies['init']>()
      .mockResolvedValueOnce(uploadInit('stale'))
      .mockResolvedValueOnce(uploadInit('fresh'))
    const getSession = vi.fn<UploadJobDependencies['getSession']>()
      .mockResolvedValueOnce(session({ uploadId: 'stale', status: 'EXPIRED' }))
      .mockResolvedValueOnce(session({ uploadId: 'fresh' }))
    const retryDependencies = successfulDependencies({ init, getSession })
    const retryJob = createUploadJob({ file, dependencies: retryDependencies })
    await retryJob.run()
    expect(init).toHaveBeenCalledTimes(2)
    expect(retryDependencies.runChunks).toHaveBeenCalledWith(expect.objectContaining({ uploadId: 'fresh' }))
  })

  it('keeps the session after complete failure and resumes on retry', async () => {
    const complete = vi.fn<UploadJobDependencies['complete']>()
      .mockRejectedValueOnce(apiError(400, 'FILE_HASH_MISMATCH'))
      .mockResolvedValueOnce({ packageId: 9, status: 'UPLOADED' })
    const init = vi.fn<UploadJobDependencies['init']>()
      .mockResolvedValueOnce(uploadInit('u1'))
      .mockResolvedValueOnce({ ...uploadInit('u1'), mode: 'RESUME', status: 'UPLOADING' })
    const dependencies = successfulDependencies({ complete, init })
    const job = createUploadJob({ file, dependencies })

    await job.run()
    expect(job.state.value.phase).toBe('error')
    expect(localStorage.getItem(`pixflow.upload.session.${fileHash}`)).not.toBeNull()

    await job.retry()
    expect(job.state.value.phase).toBe('done')
    expect(init).toHaveBeenCalledTimes(2)
  })

  it('separates pause from cancel and re-inits before resume', async () => {
    const firstRun = deferredChunkRun()
    const runChunks = vi.fn<UploadJobDependencies['runChunks']>()
      .mockImplementationOnce(firstRun.run)
      .mockResolvedValueOnce(successfulPool())
    const dependencies = successfulDependencies({ runChunks })
    const job = createUploadJob({ file, dependencies })
    const running = job.run()
    await firstRun.started

    job.pause()
    await running
    expect(job.state.value.phase).toBe('paused')
    expect(dependencies.cancel).not.toHaveBeenCalled()

    await job.resume()
    expect(dependencies.init).toHaveBeenCalledTimes(2)
    expect(job.state.value.phase).toBe('done')

    const cancelRun = deferredChunkRun()
    const cancelDependencies = successfulDependencies({ runChunks: cancelRun.run })
    const cancellable = createUploadJob({ file, dependencies: cancelDependencies })
    const inFlight = cancellable.run()
    await cancelRun.started
    await cancellable.cancel()
    await inFlight
    expect(cancellable.state.value.phase).toBe('cancelled')
    expect(cancelDependencies.cancel).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem(`pixflow.upload.session.${fileHash}`)).toBeNull()
  })
})

function successfulDependencies(overrides: Partial<UploadJobDependencies> = {}): UploadJobDependencies {
  return {
    hashFile: vi.fn<UploadJobDependencies['hashFile']>().mockResolvedValue(fileHash),
    init: vi.fn<UploadJobDependencies['init']>().mockResolvedValue(uploadInit('u1')),
    getSession: vi.fn<UploadJobDependencies['getSession']>().mockResolvedValue(session()),
    runChunks: vi.fn<UploadJobDependencies['runChunks']>().mockResolvedValue(successfulPool()),
    complete: vi.fn<UploadJobDependencies['complete']>()
      .mockResolvedValue({ packageId: 9, status: 'UPLOADED' }),
    cancel: vi.fn<UploadJobDependencies['cancel']>()
      .mockResolvedValue({ uploadId: 'u1', status: 'CANCELLED' }),
    ...overrides
  }
}

function uploadInit(uploadId: string): Extract<InitUploadResponse, { mode: 'UPLOAD' }> {
  return { mode: 'UPLOAD' as const, uploadId, packageId: null, status: null, chunkSize: 5, expectedChunks: 2, uploadedChunks: [] }
}

function session(overrides: Partial<UploadSessionState> = {}): UploadSessionState {
  return {
    uploadId: 'u1', fileHash, size: 10, chunkSize: 5, expectedChunks: 2,
    uploadedChunks: [], failedChunks: [], status: 'UPLOADING', packageId: null,
    ...overrides
  }
}

function successfulPool(): ChunkWorkerResult {
  return { uploadedIndices: new Set([0, 1]), failedIndices: new Map(), congestionFailed: new Set(), aborted: false }
}

function deferredChunkRun() {
  let signalStarted!: () => void
  const started = new Promise<void>((resolve) => { signalStarted = resolve })
  return {
    started,
    run: vi.fn<UploadJobDependencies['runChunks']>(({ signal }) => new Promise<ChunkWorkerResult>((resolve) => {
      signalStarted()
      signal.addEventListener('abort', () => resolve({ ...successfulPool(), aborted: true }), { once: true })
    }))
  }
}

function apiError(status: number, errorCode: string): ApiError {
  return new ApiError({ status, errorCode, message: errorCode, traceId: 'trace-test' })
}
