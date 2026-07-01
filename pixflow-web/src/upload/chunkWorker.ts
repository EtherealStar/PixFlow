import type { ApiError } from '@/types/api'
import type { ChunkState, PutChunkResponse, UploadSessionState } from '@/types/upload'
import { putChunk } from '@/api/packages'
import { sha256Blob } from './hasher'

/**
 * 分片 worker 池（固定大小，默认 4）。
 * 见 web.md §7.5、§7.6。
 *
 *  - 退避：1s → 2s → 4s → 8s → 16s → 30s（封顶）；同分片最多 5 次
 *  - 触发自动重试：网络错 / 5xx / 429 / UPLOAD_SESSION_NOT_UPLOADING
 *  - 不重试：CHUNK_HASH_MISMATCH / CHUNK_SIZE_MISMATCH / CHUNK_OUT_OF_RANGE / INCOMPLETE_CHUNKS
 *  - ALREADY_EXISTS 视为成功
 *  - 单分片累计退避 > 60s → FAILED → 整文件 error
 *  - AbortController：用户取消时立即中断所有在飞 PUT
 */

const BACKOFF = [1000, 2000, 4000, 8000, 16_000, 30_000]
const MAX_ATTEMPTS = 5
const CONGESTION_BUDGET_MS = 60_000

const RETRYABLE_CODES = new Set([
  'NETWORK_ERROR',
  'INTERNAL_ERROR',
  'DEPENDENCY_UNAVAILABLE',
  'UPLOAD_RATE_LIMITED',
  'UPLOAD_SESSION_NOT_UPLOADING'
])

export interface ChunkWorkerOptions {
  uploadId: string
  file: File
  chunkSize: number
  concurrency: number
  signal: AbortSignal
  onChunkUploaded?: (index: number, uploaded: number[]) => void
  onChunkFailed?: (index: number, err: ApiError) => void
}

export interface ChunkWorkerResult {
  uploadedIndices: Set<number>
  failedIndices: Map<number, ApiError>
  /** 因 60s 拥塞熔断而失败的索引 */
  congestionFailed: Set<number>
  aborted: boolean
}

export async function runChunkPool(opts: ChunkWorkerOptions): Promise<ChunkWorkerResult> {
  const { uploadId, file, chunkSize, concurrency, signal } = opts
  const total = Math.ceil(file.size / chunkSize)
  const state = new Map<number, ChunkState>()
  for (let i = 0; i < total; i++) {
    state.set(i, { index: i, phase: 'PENDING', attempts: 0, hash: '', size: 0 })
  }

  const result: ChunkWorkerResult = {
    uploadedIndices: new Set(),
    failedIndices: new Map(),
    congestionFailed: new Set(),
    aborted: false
  }

  if (signal.aborted) {
    result.aborted = true
    return result
  }
  signal.addEventListener('abort', () => {
    result.aborted = true
  })

  // 简单并发池：从 queue 取下一个 index，直到所有 PENDING/FAILED_RETRYING 都被消费
  const queue: number[] = []
  for (let i = 0; i < total; i++) queue.push(i)

  const workers: Promise<void>[] = []
  for (let w = 0; w < concurrency; w++) {
    workers.push(workerLoop(uploadId, file, chunkSize, queue, state, result, signal, opts))
  }
  await Promise.all(workers)
  return result
}

async function workerLoop(
  uploadId: string,
  file: File,
  chunkSize: number,
  queue: number[],
  state: Map<number, ChunkState>,
  result: ChunkWorkerResult,
  signal: AbortSignal,
  opts: ChunkWorkerOptions
): Promise<void> {
  while (queue.length > 0 && !result.aborted) {
    const idx = queue.shift()
    if (idx === undefined) break
    const chunk = state.get(idx)
    if (!chunk) continue
    if (chunk.phase === 'UPLOADED') continue
    await processChunk(uploadId, file, chunkSize, chunk, state, result, signal, opts)
  }
}

async function processChunk(
  uploadId: string,
  file: File,
  chunkSize: number,
  chunk: ChunkState,
  state: Map<number, ChunkState>,
  result: ChunkWorkerResult,
  signal: AbortSignal,
  opts: ChunkWorkerOptions
): Promise<void> {
  if (result.aborted || signal.aborted) return
  const start = chunk.index * chunkSize
  const end = Math.min(start + chunkSize, file.size)
  const blob = file.slice(start, end)

  // 算分片 hash
  let chunkHash: string
  try {
    chunkHash = await sha256Blob(blob)
  } catch (e) {
    const err: ApiError = {
      status: 0,
      errorCode: 'NETWORK_ERROR',
      message: e instanceof Error ? e.message : 'hash error',
      traceId: ''
    }
    state.set(chunk.index, { ...chunk, phase: 'FAILED', error: err })
    result.failedIndices.set(chunk.index, err)
    opts.onChunkFailed?.(chunk.index, err)
    return
  }

  chunk.hash = chunkHash
  chunk.size = blob.size
  chunk.phase = 'UPLOADING'
  state.set(chunk.index, chunk)

  let attempts = 0
  let totalBackoff = 0
  while (attempts < MAX_ATTEMPTS) {
    if (result.aborted || signal.aborted) return
    try {
      const resp: PutChunkResponse = await putChunk(uploadId, chunk.index, blob, chunkHash)
      const uploaded = resp.uploadedChunks ?? [chunk.index]
      chunk.phase = 'UPLOADED'
      chunk.attempts = attempts + 1
      state.set(chunk.index, chunk)
      result.uploadedIndices.add(chunk.index)
      opts.onChunkUploaded?.(chunk.index, uploaded)
      return
    } catch (e) {
      const err = e as ApiError
      // 不重试的 4xx 业务码
      if (
        err.errorCode === 'CHUNK_HASH_MISMATCH' ||
        err.errorCode === 'CHUNK_SIZE_MISMATCH' ||
        err.errorCode === 'CHUNK_OUT_OF_RANGE' ||
        err.errorCode === 'INCOMPLETE_CHUNKS' ||
        err.errorCode === 'UPLOAD_SESSION_NOT_FOUND'
      ) {
        chunk.phase = 'FAILED'
        chunk.error = err
        state.set(chunk.index, chunk)
        result.failedIndices.set(chunk.index, err)
        opts.onChunkFailed?.(chunk.index, err)
        return
      }
      attempts++
      chunk.attempts = attempts
      const isRetryable =
        err.status === 0 ||
        err.status === 429 ||
        (err.status >= 500 && err.status < 600) ||
        RETRYABLE_CODES.has(err.errorCode)
      if (!isRetryable || attempts >= MAX_ATTEMPTS) {
        chunk.phase = 'FAILED'
        chunk.error = err
        state.set(chunk.index, chunk)
        result.failedIndices.set(chunk.index, err)
        opts.onChunkFailed?.(chunk.index, err)
        return
      }
      // 退避
      const base = BACKOFF[Math.min(attempts - 1, BACKOFF.length - 1)]!
      const retryAfterMs = err.retryAfterMs ?? 0
      const delay = Math.max(base, retryAfterMs)
      totalBackoff += delay
      if (totalBackoff > CONGESTION_BUDGET_MS) {
        // 拥塞熔断
        chunk.phase = 'FAILED'
        chunk.error = err
        state.set(chunk.index, chunk)
        result.failedIndices.set(chunk.index, err)
        result.congestionFailed.add(chunk.index)
        opts.onChunkFailed?.(chunk.index, err)
        return
      }
      chunk.phase = 'FAILED_RETRYING'
      state.set(chunk.index, chunk)
      await sleep(delay, signal)
    }
  }
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) return resolve()
    const t = setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    const onAbort = () => {
      clearTimeout(t)
      resolve()
    }
    signal.addEventListener('abort', onAbort)
  })
}

/** 拉取当前 session（断线补偿用）。 */
export type { UploadSessionState }