import { request } from './client'
import { z } from 'zod'
export interface DownloadHandle {
  url: string
  expiresAt: string
  contentType: string
  sizeBytes: number
}

export type BundleItem = { referenceKey: string; filename?: string }

const downloadHandleSchema = z.strictObject({
  url: z.string().url(),
  expiresAt: z.string().min(1),
  contentType: z.literal('application/zip'),
  sizeBytes: z.number().int().nonnegative()
})

export async function createBundleDownload(items: BundleItem[], archiveName?: string): Promise<DownloadHandle> {
  return downloadHandleSchema.parse(await request<unknown>('/api/downloads/bundle', {
    method: 'POST',
    body: { items, archiveName },
    noRetry: true,
    timeoutMs: 120_000
  }))
}
