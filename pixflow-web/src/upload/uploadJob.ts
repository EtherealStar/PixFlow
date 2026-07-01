import { ref, type Ref } from 'vue'
import { completeUpload, deleteSession, getSession, initUpload } from '@/api/packages'
import type { ApiError } from '@/types/api'
import type {
  InitUploadResponse,
  UploadJobPhase,
  UploadJobState
} from '@/types/upload'
import { CHUNK_SIZE, chunkFile, expectedChunkCount } from './chunker'
import { sha256File } from './hasher'
import { deleteSession as deleteLocalSession, getSession as getLocalSession, setSession as setLocalSession } from './sessionStore'
import { runChunkPool } from './chunkWorker'
import { newId } from '@/utils/id'

/**
 * 单文件上传状态机。
 * 见 web.md §7.4。
 *
 * 状态机：idle → hashing → initing → uploading → completing → done | error | cancelled
 */

export interface UploadJobOptions {
  file: File
  concurrency?: number
  onProgress?: (p: { hashed?: number; uploaded: number; total: number; phase: UploadJobPhase }) => void
  onError?: (e: ApiError) => void
  onDedup?: (kind: 'READY' | 'UPLOADING', packageId: number | null, existingUploadId?: string) => void
  onDone?: (packageId: number) => void
}

export interface UploadJobHandle {
  state: Ref<UploadJobState>
  run: () => Promise<void>
  cancel: () => Promise<void>
  retry: () => Promise<void>
}

export function createUploadJob(opts: UploadJobOptions): UploadJobHandle {
  const concurrency = opts.concurrency ?? 4
  const state: Ref<UploadJobState> = ref({
    jobId: newId(),
    phase: 'idle',
    filename: opts.file.name,
    size: opts.file.size,
    totalChunks: expectedChunkCount(opts.file.size, CHUNK_SIZE),
    uploadedChunks: 0,
    progress: { hashed: 0, uploaded: 0, total: opts.file.size }
  })

  let abortController: AbortController | null = null
  let running = false
  let cancelled = false

  function setPhase(p: UploadJobPhase, patch: Partial<UploadJobState> = {}): void {
    state.value = { ...state.value, phase: p, ...patch }
    opts.onProgress?.({
      hashed: state.value.progress?.hashed,
      uploaded: state.value.progress?.uploaded ?? 0,
      total: state.value.size,
      phase: p
    })
  }

  function setError(err: ApiError): void {
    state.value = { ...state.value, phase: 'error', error: err }
    opts.onError?.(err)
  }

  async function run(): Promise<void> {
    if (running) return
    running = true
    cancelled = false
    abortController = new AbortController()
    const signal = abortController.signal

    try {
      // 1. hashing
      setPhase('hashing')
      const fileHash = await sha256File(opts.file, (p) => {
        state.value = {
          ...state.value,
          progress: { ...(state.value.progress ?? { uploaded: 0, total: opts.file.size }), hashed: p.hashed, uploaded: 0, total: p.total }
        }
      })
      if (cancelled) return
      state.value = { ...state.value, fileHash }

      // 2. init
      setPhase('initing')
      const totalChunks = expectedChunkCount(opts.file.size, CHUNK_SIZE)
      let initResp: InitUploadResponse
      try {
        initResp = await initUpload({
          filename: opts.file.name,
          size: opts.file.size,
          fileHash,
          chunkSize: CHUNK_SIZE
        })
      } catch (e) {
        setError(e as ApiError)
        return
      }
      if (cancelled) return

      // 整包去重
      if (initResp.mode === 'DEDUP') {
        if (initResp.status === 'READY') {
          setPhase('done', { uploadId: undefined, uploadedChunks: totalChunks, progress: { hashed: opts.file.size, uploaded: opts.file.size, total: opts.file.size } })
          opts.onDedup?.('READY', initResp.packageId)
          opts.onDone?.(initResp.packageId)
          return
        }
        // status === 'UPLOADING'：另一客户端正在上传
        opts.onDedup?.('UPLOADING', null, initResp.uploadId)
        // V1 简单做法：让用户选择；这里直接放弃，让上层组件处理
        const err: ApiError = {
          status: 409,
          errorCode: 'UPLOAD_SESSION_NOT_UPLOADING',
          message: '另一客户端正在上传同 fileHash',
          traceId: ''
        }
        setError(err)
        return
      }

      const uploadId = initResp.uploadId
      state.value = { ...state.value, uploadId }
      // 持久化
      setLocalSession(fileHash, {
        uploadId,
        filename: opts.file.name,
        size: opts.file.size,
        chunkSize: CHUNK_SIZE,
        savedAt: Date.now()
      })

      // 3. uploading
      setPhase('uploading')
      // 拉一次 session 校准 uploadedChunks（应对其他客户端并发上传）
      try {
        const session = await getSession(uploadId)
        if (session.status === 'READY' || session.status === 'CANCELLED' || session.status === 'EXPIRED') {
          if (session.status === 'READY' && session.packageId) {
            setPhase('done', { uploadedChunks: session.uploadedChunks.length, progress: { hashed: opts.file.size, uploaded: opts.file.size, total: opts.file.size } })
            opts.onDone?.(session.packageId)
            return
          }
          if (session.status === 'CANCELLED' || session.status === 'EXPIRED') {
            deleteLocalSession(fileHash)
            const err: ApiError = { status: 409, errorCode: 'UPLOAD_SESSION_NOT_UPLOADING', message: `session ${session.status}`, traceId: '' }
            setError(err)
            return
          }
        }
      } catch {
        // 拉不到 session → 用 init 响应里的 uploadedChunks
      }

      const poolResult = await runChunkPool({
        uploadId,
        file: opts.file,
        chunkSize: CHUNK_SIZE,
        concurrency,
        signal,
        onChunkUploaded: (_idx, uploaded) => {
          state.value = {
            ...state.value,
            uploadedChunks: uploaded.length,
            progress: { ...(state.value.progress ?? { hashed: opts.file.size, total: opts.file.size }), uploaded: uploaded.length * CHUNK_SIZE > opts.file.size ? opts.file.size : uploaded.length * CHUNK_SIZE, total: opts.file.size }
          }
        },
        onChunkFailed: (_idx, err) => {
          if (err.errorCode === 'UPLOAD_SESSION_NOT_UPLOADING' || err.errorCode === 'UPLOAD_SESSION_NOT_FOUND') {
            // 整文件 → cancelled
            setPhase('cancelled', { error: err })
          }
        }
      })
      if (cancelled) return
      if (poolResult.aborted) {
        setPhase('cancelled')
        return
      }
      if (poolResult.failedIndices.size > 0) {
        const firstErr = poolResult.failedIndices.values().next().value as ApiError
        setError(firstErr)
        return
      }

      // 4. completing
      setPhase('completing')
      let completeResp
      try {
        completeResp = await completeUpload(uploadId, fileHash)
      } catch (e) {
        setError(e as ApiError)
        return
      }

      // 5. done
      deleteLocalSession(fileHash)
      setPhase('done', { progress: { hashed: opts.file.size, uploaded: opts.file.size, total: opts.file.size } })
      opts.onDone?.(completeResp.packageId)
    } catch (e) {
      setError(e as ApiError)
    } finally {
      running = false
    }
  }

  async function cancel(): Promise<void> {
    cancelled = true
    if (abortController) {
      abortController.abort()
    }
    const uploadId = state.value.uploadId
    if (uploadId) {
      try {
        await deleteSession(uploadId)
      } catch {
        // fire-and-forget；错误仅记 breadcrumb
      }
    }
    if (state.value.fileHash) {
      deleteLocalSession(state.value.fileHash)
    }
    setPhase('cancelled')
  }

  async function retry(): Promise<void> {
    if (state.value.phase === 'uploading' || state.value.phase === 'hashing' || state.value.phase === 'initing') {
      return
    }
    state.value = {
      ...state.value,
      phase: 'idle',
      error: undefined,
      uploadedChunks: 0
    }
    await run()
  }

  return { state, run, cancel, retry }
}

/** 工具：恢复已存在的本地 session（用于"续传"路径）。 */
export function resumeLocalSession(fileHash: string): { uploadId: string; filename: string; size: number; chunkSize: number } | null {
  return getLocalSession(fileHash)
}