import { request } from './client'

/**
 * 上传会话附件（multipart）。
 * 见 api.md `Attachment API`。
 */
export interface UploadAttachmentResponse {
  attachmentId: string
  sourceRef: string
  type: 'UPLOAD_IMAGE'
}

export function uploadAttachment(
  conversationId: string,
  file: File,
  metadata: { filename?: string; contentType?: string } = {}
): Promise<UploadAttachmentResponse> {
  const form = new FormData()
  form.append('file', file)
  form.append('metadata', JSON.stringify({
    filename: metadata.filename ?? file.name,
    contentType: metadata.contentType ?? file.type
  }))
  return request<UploadAttachmentResponse>(
    `/api/conversations/${encodeURIComponent(conversationId)}/attachments`,
    { method: 'POST', multipart: true, body: form, noRetry: true }
  )
}