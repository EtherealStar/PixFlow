import { request } from './client'
import { z } from 'zod'
import type {
  CompleteUploadResponse,
  InitUploadResponse,
  PutChunkResponse,
  UploadSessionState,
} from '@/types/upload'

/**
 * 素材包分片上传与包查询/删除。
 * 见 api.md `Asset Package API`。
 */

export interface InitUploadRequest {
  filename: string
  size: number
  fileHash: string
  chunkSize: number
}

const initUploadResponseSchema = z.discriminatedUnion('mode', [
  z.strictObject({ mode: z.literal('UPLOAD'), uploadId: z.string().min(1), packageId: z.null(), status: z.null(), chunkSize: z.number().int().positive(), expectedChunks: z.number().int().positive(), uploadedChunks: z.array(z.number().int().nonnegative()) }),
  z.strictObject({ mode: z.literal('RESUME'), uploadId: z.string().min(1), packageId: z.null(), status: z.literal('UPLOADING'), chunkSize: z.number().int().positive(), expectedChunks: z.number().int().positive(), uploadedChunks: z.array(z.number().int().nonnegative()) }),
  z.strictObject({ mode: z.literal('DEDUP'), uploadId: z.null(), packageId: z.number().int().positive(), status: z.literal('READY'), chunkSize: z.literal(0), expectedChunks: z.literal(0), uploadedChunks: z.tuple([]) })
])

const uploadSessionSchema = z.strictObject({
  uploadId: z.string().min(1), fileHash: z.string().min(1), size: z.number().int().nonnegative(),
  chunkSize: z.number().int().positive(), expectedChunks: z.number().int().positive(),
  uploadedChunks: z.array(z.number().int().nonnegative()), failedChunks: z.array(z.number().int().nonnegative()),
  status: z.enum(['UPLOADING', 'READY', 'EXPIRED', 'CANCELLED']), packageId: z.number().int().positive().nullable()
})

const putChunkResponseSchema = z.strictObject({
  uploadId: z.string().min(1), index: z.number().int().nonnegative(),
  status: z.enum(['ACCEPTED', 'ALREADY_EXISTS']), uploadedChunks: z.array(z.number().int().nonnegative())
})

const completeUploadResponseSchema = z.strictObject({ packageId: z.number().int().positive(), status: z.literal('UPLOADED') })
const cancelUploadResponseSchema = z.strictObject({ uploadId: z.string().min(1), status: z.literal('CANCELLED') })

export async function initUpload(req: InitUploadRequest, signal?: AbortSignal): Promise<InitUploadResponse> {
  return initUploadResponseSchema.parse(await request<unknown>('/api/files/packages/init', {
    method: 'POST',
    body: req,
    noRetry: true,
    signal
  }))
}

export async function getSession(uploadId: string, signal?: AbortSignal): Promise<UploadSessionState> {
  return uploadSessionSchema.parse(await request<unknown>(`/api/files/packages/sessions/${encodeURIComponent(uploadId)}`, { signal }))
}

export async function putChunk(
  uploadId: string,
  index: number,
  chunk: Blob,
  chunkHash: string,
  signal?: AbortSignal
): Promise<PutChunkResponse> {
  return putChunkResponseSchema.parse(await request<unknown>(
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
      signal,
      skipInFlight: true // worker 池限流
    }
  ))
}

export async function completeUpload(uploadId: string, fileHash?: string, signal?: AbortSignal): Promise<CompleteUploadResponse> {
  return completeUploadResponseSchema.parse(await request<unknown>(
    `/api/files/packages/sessions/${encodeURIComponent(uploadId)}/complete`,
    { method: 'POST', body: fileHash ? { fileHash } : {}, noRetry: true, signal }
  ))
}

export async function deleteSession(uploadId: string): Promise<{ uploadId: string; status: 'CANCELLED' }> {
  return cancelUploadResponseSchema.parse(await request<unknown>(
    `/api/files/packages/sessions/${encodeURIComponent(uploadId)}`,
    { method: 'DELETE', noRetry: true, skipInFlight: true }
  ))
}
