import type { ApiError } from './api'

/** 整文件上传状态机阶段。 */
export type UploadJobPhase =
  | 'idle'
  | 'hashing'
  | 'initing'
  | 'uploading'
  | 'paused'
  | 'completing'
  | 'done'
  | 'error'
  | 'cancelled'

/** 上传会话摘要（Pinia 持久化部分）。 */
export interface UploadJobState {
  jobId: string
  phase: UploadJobPhase
  fileHash?: string
  uploadId?: string
  filename: string
  size: number
  totalChunks: number
  uploadedChunks: number
  progress?: { hashed: number; uploaded: number; total: number }
  error?: ApiError
}

/** 单分片状态（uploadJob 内部）。 */
export type ChunkPhase = 'PENDING' | 'UPLOADING' | 'UPLOADED' | 'FAILED' | 'FAILED_RETRYING'

export interface ChunkState {
  index: number
  phase: ChunkPhase
  attempts: number
  hash: string
  size: number
  error?: ApiError
}

/** init 响应严格对应后端 UPLOAD / RESUME / DEDUP 三种互斥形态。 */
export type InitUploadResponse =
  | { mode: 'UPLOAD'; uploadId: string; packageId: null; status: null; chunkSize: number; expectedChunks: number; uploadedChunks: number[] }
  | { mode: 'RESUME'; uploadId: string; packageId: null; status: 'UPLOADING'; chunkSize: number; expectedChunks: number; uploadedChunks: number[] }
  | { mode: 'DEDUP'; uploadId: null; packageId: number; status: 'READY'; chunkSize: 0; expectedChunks: 0; uploadedChunks: [] }

/** 拉取会话响应。 */
export interface UploadSessionState {
  uploadId: string
  fileHash: string
  size: number
  chunkSize: number
  expectedChunks: number
  uploadedChunks: number[]
  failedChunks: number[]
  status: 'UPLOADING' | 'READY' | 'EXPIRED' | 'CANCELLED'
  packageId: number | null
}

/** chunk PUT 响应。 */
export interface PutChunkResponse {
  uploadId: string
  index: number
  status: 'ACCEPTED' | 'ALREADY_EXISTS'
  uploadedChunks: number[]
}

/** complete 响应。 */
export interface CompleteUploadResponse {
  packageId: number
  status: 'UPLOADED'
}

export type ViewMode = 'folder' | 'flat'
export type SortType = 'time' | 'name'
