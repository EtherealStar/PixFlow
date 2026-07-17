import { ref, type Ref } from 'vue'
import { completeUpload, deleteSession, getSession, initUpload } from '@/api/packages'
import { ApiError } from '@/types/api'
import type { InitUploadResponse, UploadJobPhase, UploadJobState, UploadSessionState } from '@/types/upload'
import { CHUNK_SIZE, expectedChunkCount } from './chunker'
import { isAbortError, sha256File } from './hasher'
import { deleteSession as deleteLocalSession, getSession as getLocalSession, setSession as setLocalSession } from './sessionStore'
import { runChunkPool } from './chunkWorker'
import { newId } from '@/utils/id'

export interface UploadJobOptions {
  file: File
  concurrency?: number
  onProgress?: (progress: { hashed?: number; uploaded: number; total: number; phase: UploadJobPhase }) => void
  onError?: (error: ApiError) => void
  onDedup?: (kind: 'READY', packageId: number) => void
  onDone?: (packageId: number) => void
  /** 测试缝：生产调用不传，协议编排仍只存在于本模块。 */
  dependencies?: Partial<UploadJobDependencies>
}

export interface UploadJobDependencies {
  hashFile: typeof sha256File
  init: typeof initUpload
  getSession: typeof getSession
  runChunks: typeof runChunkPool
  complete: typeof completeUpload
  cancel: typeof deleteSession
}

export interface UploadJobHandle {
  state: Ref<UploadJobState>
  run: () => Promise<void>
  pause: () => void
  resume: () => Promise<void>
  cancel: () => Promise<void>
  retry: () => Promise<void>
}

export function createUploadJob(options: UploadJobOptions): UploadJobHandle {
  const dependencies: UploadJobDependencies = {
    hashFile: sha256File,
    init: initUpload,
    getSession,
    runChunks: runChunkPool,
    complete: completeUpload,
    cancel: deleteSession,
    ...options.dependencies
  }
  const concurrency = options.concurrency ?? 4
  const state: Ref<UploadJobState> = ref({
    jobId: newId(),
    phase: 'idle',
    filename: options.file.name,
    size: options.file.size,
    totalChunks: expectedChunkCount(options.file.size, CHUNK_SIZE),
    uploadedChunks: 0,
    progress: { hashed: 0, uploaded: 0, total: options.file.size }
  })

  let generation = 0
  let abortController: AbortController | null = null
  let cachedFileHash: string | undefined

  function isCurrent(runGeneration: number): boolean {
    return generation === runGeneration
  }

  function setPhase(runGeneration: number, phase: UploadJobPhase, patch: Partial<UploadJobState> = {}): void {
    if (!isCurrent(runGeneration)) return
    state.value = { ...state.value, ...patch, phase }
    emitProgress(phase)
  }

  function patchProgress(runGeneration: number, hashed: number, uploaded: number): void {
    if (!isCurrent(runGeneration)) return
    state.value = {
      ...state.value,
      progress: { hashed, uploaded, total: options.file.size }
    }
    emitProgress(state.value.phase)
  }

  function emitProgress(phase: UploadJobPhase): void {
    options.onProgress?.({
      hashed: state.value.progress?.hashed,
      uploaded: state.value.progress?.uploaded ?? 0,
      total: options.file.size,
      phase
    })
  }

  function setError(runGeneration: number, error: ApiError): void {
    if (!isCurrent(runGeneration)) return
    state.value = { ...state.value, phase: 'error', error }
    options.onError?.(error)
    emitProgress('error')
  }

  async function run(): Promise<void> {
    if (isBusy(state.value.phase)) return
    const runGeneration = ++generation
    abortController?.abort()
    abortController = new AbortController()
    const signal = abortController.signal

    try {
      setPhase(runGeneration, 'hashing', { error: undefined })
      const fileHash = cachedFileHash ?? await dependencies.hashFile(options.file, (progress) => {
        patchProgress(runGeneration, progress.hashed, state.value.progress?.uploaded ?? 0)
      }, signal)
      if (!isCurrent(runGeneration)) return
      cachedFileHash = fileHash
      state.value = { ...state.value, fileHash }

      setPhase(runGeneration, 'initing')
      const target = await resolveUploadTarget(fileHash, runGeneration, signal)
      if (!target || !isCurrent(runGeneration)) return
      if (target.kind === 'done') {
        deleteLocalSession(fileHash)
        setPhase(runGeneration, 'done', {
          uploadId: undefined,
          uploadedChunks: state.value.totalChunks,
          progress: { hashed: options.file.size, uploaded: options.file.size, total: options.file.size }
        })
        if (target.dedup) options.onDedup?.('READY', target.packageId)
        options.onDone?.(target.packageId)
        return
      }

      const { uploadId, chunkSize, expectedChunks, uploadedChunks } = target
      state.value = { ...state.value, uploadId, totalChunks: expectedChunks }
      setLocalSession(fileHash, {
        uploadId,
        filename: options.file.name,
        size: options.file.size,
        chunkSize,
        savedAt: Date.now()
      })

      setPhase(runGeneration, 'uploading')
      const poolResult = await dependencies.runChunks({
        uploadId,
        file: options.file,
        chunkSize,
        expectedChunks,
        initialUploadedIndices: uploadedChunks,
        concurrency,
        signal,
        onProgress: ({ uploadedIndices, uploadedBytes }) => {
          if (!isCurrent(runGeneration)) return
          state.value = { ...state.value, uploadedChunks: uploadedIndices.length }
          patchProgress(runGeneration, options.file.size, uploadedBytes)
        }
      })
      if (!isCurrent(runGeneration)) return
      if (poolResult.aborted) return
      if (poolResult.failedIndices.size > 0) {
        setError(runGeneration, poolResult.failedIndices.values().next().value!)
        return
      }

      setPhase(runGeneration, 'completing')
      const completed = await dependencies.complete(uploadId, fileHash, signal)
      if (!isCurrent(runGeneration)) return
      deleteLocalSession(fileHash)
      setPhase(runGeneration, 'done', {
        uploadedChunks: expectedChunks,
        progress: { hashed: options.file.size, uploaded: options.file.size, total: options.file.size }
      })
      options.onDone?.(completed.packageId)
    } catch (error) {
      if (!isCurrent(runGeneration) || isAbortError(error)) return
      setError(runGeneration, asApiError(error))
    }
  }

  async function resolveUploadTarget(
    fileHash: string,
    runGeneration: number,
    signal: AbortSignal
  ): Promise<UploadTarget | null> {
    // localStorage 只作为恢复提示；是否续传及完成集合始终由后端决定。
    void getLocalSession(fileHash)
    for (let attempt = 0; attempt < 2; attempt++) {
      const initResponse = await dependencies.init({
        filename: options.file.name,
        size: options.file.size,
        fileHash,
        chunkSize: CHUNK_SIZE
      }, signal)
      if (!isCurrent(runGeneration) || signal.aborted) return null
      if (initResponse.mode === 'DEDUP') {
        return { kind: 'done', packageId: initResponse.packageId, dedup: true }
      }

      let uploadedChunks = initResponse.uploadedChunks
      try {
        const session = await dependencies.getSession(initResponse.uploadId, signal)
        if (!isCurrent(runGeneration) || signal.aborted) return null
        const resolved = resolveSession(session, initResponse)
        if (resolved.kind === 'retry-init') {
          deleteLocalSession(fileHash)
          if (attempt === 0) continue
          throw sessionEndedError(session.status)
        }
        if (resolved.kind === 'done') return resolved
        uploadedChunks = resolved.uploadedChunks
      } catch (error) {
        const apiError = asApiError(error)
        if (apiError.status === 404 || apiError.status === 409) {
          deleteLocalSession(fileHash)
          if (attempt === 0) continue
          throw apiError
        }
        // GET 的网络/5xx 失败可退回 init 快照；重复 PUT 由后端幂等保护。
        if (!(apiError.status === 0 || apiError.status >= 500)) throw apiError
      }
      return {
        kind: 'upload',
        uploadId: initResponse.uploadId,
        chunkSize: initResponse.chunkSize,
        expectedChunks: initResponse.expectedChunks,
        uploadedChunks
      }
    }
    return null
  }

  function pause(): void {
    if (!isBusy(state.value.phase)) return
    generation++
    abortController?.abort()
    state.value = { ...state.value, phase: 'paused', error: undefined }
    emitProgress('paused')
  }

  async function resume(): Promise<void> {
    if (state.value.phase !== 'paused') return
    await run()
  }

  async function cancel(): Promise<void> {
    if (state.value.phase === 'done' || state.value.phase === 'cancelled') return
    const uploadId = state.value.uploadId
    const fileHash = state.value.fileHash
    generation++
    abortController?.abort()
    if (fileHash) deleteLocalSession(fileHash)
    // UI 先进入终态，服务端清理由下方 best-effort 完成。
    state.value = { ...state.value, phase: 'cancelled', error: undefined }
    emitProgress('cancelled')
    if (!uploadId) return
    try {
      await dependencies.cancel(uploadId)
    } catch {
      // 取消语义不能被清理请求失败反转；服务端 TTL 仍会兜底回收。
    }
  }

  async function retry(): Promise<void> {
    if (state.value.phase !== 'error') return
    await run()
  }

  return { state, run, pause, resume, cancel, retry }
}

type UploadTarget =
  | { kind: 'done'; packageId: number; dedup: boolean }
  | { kind: 'upload'; uploadId: string; chunkSize: number; expectedChunks: number; uploadedChunks: number[] }

function resolveSession(session: UploadSessionState, init: Exclude<InitUploadResponse, { mode: 'DEDUP' }>): UploadTarget | { kind: 'retry-init' } {
  if (session.status === 'READY' && session.packageId !== null && session.packageId !== undefined) {
    return { kind: 'done', packageId: session.packageId, dedup: false }
  }
  if (session.status === 'CANCELLED' || session.status === 'EXPIRED') return { kind: 'retry-init' }
  return {
    kind: 'upload',
    uploadId: init.uploadId,
    chunkSize: session.chunkSize,
    expectedChunks: session.expectedChunks,
    uploadedChunks: session.uploadedChunks
  }
}

function isBusy(phase: UploadJobPhase): boolean {
  return phase === 'hashing' || phase === 'initing' || phase === 'uploading' || phase === 'completing'
}

function sessionEndedError(status: UploadSessionState['status']): ApiError {
  return new ApiError({ status: 409, errorCode: 'UPLOAD_SESSION_NOT_UPLOADING', message: `上传会话已结束：${status}`, traceId: '' })
}

function asApiError(error: unknown): ApiError {
  return ApiError.fromUnknown(error, {
    status: 0,
    errorCode: 'NETWORK_ERROR',
    message: '上传失败',
    traceId: ''
  })
}

export function resumeLocalSession(fileHash: string): { uploadId: string; filename: string; size: number; chunkSize: number } | null {
  return getLocalSession(fileHash)
}
