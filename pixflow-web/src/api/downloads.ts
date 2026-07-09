import { request } from './client'
import type { DownloadHandle } from '@/api/tasks'

export type BundleItem =
  | { type: 'ASSET_IMAGE'; imageId: string; filename?: string }
  | { type: 'TASK_RESULT'; resultId: string; filename?: string }

export function createBundleDownload(items: BundleItem[], archiveName?: string): Promise<DownloadHandle> {
  return request<DownloadHandle>('/api/downloads/bundle', {
    method: 'POST',
    body: { items, archiveName },
    noRetry: true,
    timeoutMs: 120_000
  })
}
