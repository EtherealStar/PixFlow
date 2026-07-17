import { ApiError } from '@/types/api'
import type { PutChunkResponse } from '@/types/upload'
import { putChunk as putChunkApi } from '@/api/packages'
import { sha256Blob } from './hasher'

const BACKOFF = [1000, 2000, 4000, 8000, 16_000]
const MAX_ATTEMPTS = 5
const CONGESTION_BUDGET_MS = 60_000

export interface ChunkWorkerOptions {
  uploadId: string
  file: File
  chunkSize: number
  expectedChunks: number
  initialUploadedIndices: Iterable<number>
  concurrency: number
  signal: AbortSignal
  onProgress?: (snapshot: { uploadedIndices: number[]; uploadedBytes: number }) => void
  onChunkFailed?: (index: number, error: ApiError) => void
  /** 测试缝：生产调用不传。 */
  putChunk?: typeof putChunkApi
  hashChunk?: typeof sha256Blob
  wait?: (ms: number, signal: AbortSignal) => Promise<void>
}

export interface ChunkWorkerResult {
  uploadedIndices: Set<number>
  failedIndices: Map<number, ApiError>
  congestionFailed: Set<number>
  aborted: boolean
}

/**
 * 只调度后端尚未确认的分片。响应中的 uploadedChunks 会持续校准共享快照。
 */
export async function runChunkPool(options: ChunkWorkerOptions): Promise<ChunkWorkerResult> {
  const uploadedIndices = normalizeIndices(options.initialUploadedIndices, options.expectedChunks)
  const result: ChunkWorkerResult = {
    uploadedIndices,
    failedIndices: new Map(),
    congestionFailed: new Set(),
    aborted: options.signal.aborted
  }
  if (result.aborted) return result

  const queue = Array.from({ length: options.expectedChunks }, (_, index) => index)
    .filter((index) => !uploadedIndices.has(index))
  let cursor = 0
  let stopped = false
  const workerCount = Math.max(1, Math.min(options.concurrency, queue.length || 1))

  emitProgress(options, result.uploadedIndices)

  async function workerLoop(): Promise<void> {
    while (!stopped && !options.signal.aborted) {
      const position = cursor++
      if (position >= queue.length) return
      const index = queue[position]!
      const error = await uploadOne(index, options, result)
      if (error) {
        result.failedIndices.set(index, error)
        options.onChunkFailed?.(index, error)
        // 一个确定性失败就停止领取新任务，避免终止会话后继续制造无效 PUT。
        stopped = true
      }
    }
  }

  await Promise.all(Array.from({ length: workerCount }, () => workerLoop()))
  result.aborted = options.signal.aborted
  return result
}

async function uploadOne(
  index: number,
  options: ChunkWorkerOptions,
  result: ChunkWorkerResult
): Promise<ApiError | null> {
  const start = index * options.chunkSize
  const end = Math.min(start + options.chunkSize, options.file.size)
  const blob = options.file.slice(start, end)
  const hashChunk = options.hashChunk ?? sha256Blob
  const sendChunk = options.putChunk ?? putChunkApi
  const wait = options.wait ?? sleep

  let chunkHash: string
  try {
    chunkHash = await hashChunk(blob)
  } catch (error) {
    if (options.signal.aborted) return null
    return asApiError(error, '分片哈希计算失败')
  }

  let totalBackoff = 0
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    if (options.signal.aborted) return null
    try {
      const response = await sendChunk(options.uploadId, index, blob, chunkHash, options.signal)
      mergeServerSnapshot(result.uploadedIndices, response, options.expectedChunks)
      // 某些兼容响应不回完整集合，当前成功 index 仍必须计入。
      result.uploadedIndices.add(index)
      emitProgress(options, result.uploadedIndices)
      return null
    } catch (error) {
      const apiError = asApiError(error, '分片上传失败')
      if (!isRetryable(apiError) || attempt === MAX_ATTEMPTS) return apiError

      const delay = BACKOFF[Math.min(attempt - 1, BACKOFF.length - 1)]!
      totalBackoff += delay
      if (totalBackoff > CONGESTION_BUDGET_MS) {
        result.congestionFailed.add(index)
        return apiError
      }
      await wait(delay, options.signal)
    }
  }
  return null
}

function isRetryable(error: ApiError): boolean {
  return error.status === 0 || (error.status >= 500 && error.status < 600)
}

function mergeServerSnapshot(target: Set<number>, response: PutChunkResponse, expectedChunks: number): void {
  for (const index of response.uploadedChunks ?? []) {
    if (Number.isInteger(index) && index >= 0 && index < expectedChunks) target.add(index)
  }
}

function normalizeIndices(indices: Iterable<number>, expectedChunks: number): Set<number> {
  const normalized = new Set<number>()
  for (const index of indices) {
    if (Number.isInteger(index) && index >= 0 && index < expectedChunks) normalized.add(index)
  }
  return normalized
}

function emitProgress(options: ChunkWorkerOptions, indices: Set<number>): void {
  const snapshot = [...indices].sort((a, b) => a - b)
  const uploadedBytes = snapshot.reduce((sum, index) => {
    const start = index * options.chunkSize
    return sum + Math.max(0, Math.min(options.chunkSize, options.file.size - start))
  }, 0)
  options.onProgress?.({ uploadedIndices: snapshot, uploadedBytes })
}

function asApiError(error: unknown, fallback: string): ApiError {
  return ApiError.fromUnknown(error, {
    status: 0,
    errorCode: 'NETWORK_ERROR',
    message: fallback,
    traceId: ''
  })
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve()
    const timer = setTimeout(done, ms)
    function done() {
      clearTimeout(timer)
      signal.removeEventListener('abort', done)
      resolve()
    }
    signal.addEventListener('abort', done, { once: true })
  })
}
