import { uploadWholeFile } from './packages'

/**
 * 会话附件上传兼容入口。
 * 后端没有 `/api/conversations/{id}/attachments`，这里复用素材包上传端口，
 * 上传完成后返回可直接用于 `POST /messages` 顶层字段的 packageId。
 */
export interface UploadAttachmentResponse {
  packageId: string
  metadata: Record<string, unknown>
  status: string
}

export function uploadAttachment(
  conversationId: string,
  file: File,
  metadata: { filename?: string; contentType?: string } = {}
): Promise<UploadAttachmentResponse> {
  void conversationId
  return uploadWholeFile(file).then((resp) => {
    const packageId = String(resp.packageId)
    return {
      packageId,
      metadata: {
        filename: metadata.filename ?? file.name,
        contentType: metadata.contentType ?? file.type
      },
      status: resp.status
    }
  })
}
