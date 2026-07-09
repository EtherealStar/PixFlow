import { request } from './client'
import type {
  CompleteUploadResponse,
  InitUploadResponse,
  PackageDetail,
  AssetImageView,
  PutChunkResponse,
  UploadSessionState,
  WholeFileUploadResponse
} from '@/types/upload'
import type { Page } from '@/types/api'

/**
 * 素材包分片上传 + 整文件上传兼容 + 包查询/删除。
 * 见 api.md `Asset Package API`。
 */

export interface InitUploadRequest {
  filename: string
  size: number
  fileHash: string
  chunkSize: number
}

export function initUpload(req: InitUploadRequest): Promise<InitUploadResponse> {
  return request<InitUploadResponse>('/api/files/packages/init', {
    method: 'POST',
    body: req,
    noRetry: true
  })
}

export function getSession(uploadId: string): Promise<UploadSessionState> {
  return request<UploadSessionState>(`/api/files/packages/sessions/${encodeURIComponent(uploadId)}`)
}

export function putChunk(
  uploadId: string,
  index: number,
  chunk: Blob,
  chunkHash: string
): Promise<PutChunkResponse> {
  return request<PutChunkResponse>(
    `/api/files/packages/sessions/${encodeURIComponent(uploadId)}/chunks/${index}`,
    {
      method: 'PUT',
      body: chunk,
      headers: {
        'Content-Type': 'application/octet-stream',
        'X-Chunk-Hash': chunkHash,
        'X-Chunk-Size': String(chunk.size)
      },
      noRetry: true,
      skipInFlight: true // worker 池限流
    }
  )
}

export function completeUpload(uploadId: string, fileHash?: string): Promise<CompleteUploadResponse> {
  return request<CompleteUploadResponse>(
    `/api/files/packages/sessions/${encodeURIComponent(uploadId)}/complete`,
    { method: 'POST', body: fileHash ? { fileHash } : {}, noRetry: true }
  )
}

export function deleteSession(uploadId: string): Promise<{ uploadId: string; status: 'CANCELLED' }> {
  return request<{ uploadId: string; status: 'CANCELLED' }>(
    `/api/files/packages/sessions/${encodeURIComponent(uploadId)}`,
    { method: 'DELETE', noRetry: true, skipInFlight: true }
  )
}

export function uploadWholeFile(zip: File, doc?: File): Promise<WholeFileUploadResponse> {
  const form = new FormData()
  form.append('zip', zip)
  if (doc) form.append('doc', doc)
  return request<WholeFileUploadResponse>('/api/files/packages', {
    method: 'POST',
    multipart: true,
    body: form,
    noRetry: true
  })
}

interface BackendPackageDetail extends Omit<Partial<PackageDetail>, 'packageId'> {
  id?: number
  packageId?: number
}

function normalizePackage(raw: BackendPackageDetail): PackageDetail {
  // 后端 AssetPackage 序列化主键为 id；页面统一使用 packageId。
  const packageId = raw.packageId ?? raw.id
  if (packageId == null) {
    throw new Error('素材包响应缺少 id/packageId')
  }
  return {
    ...raw,
    packageId,
    name: raw.name ?? `package-${packageId}`,
    status: raw.status ?? 'UPLOADED'
  }
}

function normalizePackagePage(page: Page<BackendPackageDetail>): Page<PackageDetail> {
  const items = (page.items ?? page.records ?? []).map(normalizePackage)
  return { ...page, items, records: items }
}

function assertNumericPathId(name: string, value: number | string): string {
  const text = String(value)
  if (!/^\d+$/.test(text)) {
    throw new Error(`${name} 必须是数字 ID`)
  }
  return text
}

export async function getPackage(packageId: number): Promise<PackageDetail> {
  const raw = await request<BackendPackageDetail>(`/api/files/packages/${packageId}`)
  return normalizePackage(raw)
}

export async function listPackages(params: { page?: number; size?: number } = {}): Promise<Page<PackageDetail>> {
  const q = new URLSearchParams()
  if (params.page !== undefined) q.set('page', String(params.page))
  if (params.size !== undefined) q.set('size', String(params.size))
  const qs = q.toString()
  const page = await request<Page<BackendPackageDetail>>(`/api/files/packages${qs ? `?${qs}` : ''}`)
  return normalizePackagePage(page)
}

export function listPackageImages(packageId: number, params: { page?: number; size?: number } = {}): Promise<Page<AssetImageView>> {
  const q = new URLSearchParams()
  if (params.page !== undefined) q.set('page', String(params.page))
  if (params.size !== undefined) q.set('size', String(params.size))
  const qs = q.toString()
  return request<Page<AssetImageView>>(`/api/files/packages/${packageId}/images${qs ? `?${qs}` : ''}`)
}

export function deletePackage(packageId: number): Promise<void> {
  return request<void>(`/api/files/packages/${packageId}`, { method: 'DELETE', noRetry: true })
}

export function deletePackageImage(packageId: number, imageId: string): Promise<void> {
  const numericImageId = assertNumericPathId('imageId', imageId)
  return request<void>(`/api/files/packages/${packageId}/images/${encodeURIComponent(numericImageId)}`, {
    method: 'DELETE',
    noRetry: true
  })
}

export function renamePackageImage(packageId: number, imageId: string, displayName: string): Promise<AssetImageView> {
  const numericImageId = assertNumericPathId('imageId', imageId)
  return request<AssetImageView>(`/api/files/packages/${packageId}/images/${encodeURIComponent(numericImageId)}`, {
    method: 'PATCH',
    body: { displayName },
    noRetry: true
  })
}

export function getPackageErrors(packageId: number, params: { page?: number; size?: number } = {}): Promise<Page<{ originalPath: string; stage: string; code: string; message: string; createdAt: string }>> {
  const q = new URLSearchParams()
  if (params.page !== undefined) q.set('page', String(params.page))
  if (params.size !== undefined) q.set('size', String(params.size))
  const qs = q.toString()
  return request<Page<{ originalPath: string; stage: string; code: string; message: string; createdAt: string }>>(
    `/api/files/packages/${packageId}/errors${qs ? `?${qs}` : ''}`
  )
}
