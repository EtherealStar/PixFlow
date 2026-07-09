import type { ApiError } from './api'

/** 整文件上传状态机阶段。 */
export type UploadJobPhase =
  | 'idle'
  | 'hashing'
  | 'initing'
  | 'uploading'
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

/** init 响应：UPLOAD / DEDUP-READY / DEDUP-UPLOADING 三种。 */
export type InitUploadResponse =
  | { mode: 'UPLOAD'; uploadId: string; chunkSize: number; expectedChunks: number; uploadedChunks: number[] }
  | { mode: 'DEDUP'; packageId: number; status: 'READY' }
  | { mode: 'DEDUP'; packageId: null; status: 'UPLOADING'; uploadId: string; uploadedChunks: number[] }

/** 拉取会话响应。 */
export interface UploadSessionState {
  uploadId: string
  fileHash?: string
  size?: number
  chunkSize?: number
  expectedChunks: number
  uploadedChunks: number[]
  failedChunks: number[]
  status: 'UPLOADING' | 'READY' | 'EXPIRED' | 'CANCELLED'
  packageId?: number | null
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

/** 整文件上传兼容响应。 */
export interface WholeFileUploadResponse {
  packageId: number
  status: 'UPLOADED' | 'EXTRACTING' | 'READY' | 'PARTIAL' | 'FAILED'
  messageConfirmed?: boolean
}

/** 素材包详情响应。 */
export interface PackageDetail {
  id?: number
  packageId: number
  name: string
  status: 'UPLOADED' | 'EXTRACTING' | 'READY' | 'PARTIAL' | 'FAILED'
  imageCount?: number
  extractedCount?: number
  errorSummary?: string
  fileHash?: string
  minioZipKey?: string
  docKey?: string | null
  deletedAt?: string | null
  createdAt?: string
  updatedAt?: string
  images?: ImageItem[]
}

export interface ImageItem {
  id: string
  url: string
  name: string
  createdAt: string
}

export interface AssetImageView {
  imageId: string
  packageId: number
  filename: string
  displayName?: string | null
  originalPath: string
  skuId?: string | null
  groupKey?: string | null
  viewId?: string | null
  size?: number | null
  url: string
  createdAt: string
}

export type ViewMode = 'folder' | 'flat'
export type SortType = 'time' | 'name'
