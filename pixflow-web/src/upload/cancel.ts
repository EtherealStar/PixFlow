/**
 * 用户取消编排辅助。
 * 见 web.md §7.7。
 * AbortController.abort() + DELETE + 清 sessionStore + UI 立即 cancelled。
 */
import { deleteSession as deleteRemoteSession } from '@/api/packages'
import { deleteSession as deleteLocalSession } from './sessionStore'

export interface CancelOptions {
  uploadId?: string
  fileHash?: string
}

export async function performCancel(opts: CancelOptions): Promise<void> {
  if (opts.uploadId) {
    try {
      await deleteRemoteSession(opts.uploadId)
    } catch {
      // fire-and-forget；DELETE 失败也不打断 UI
    }
  }
  if (opts.fileHash) {
    deleteLocalSession(opts.fileHash)
  }
}