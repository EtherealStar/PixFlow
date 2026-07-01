import { request } from './client'

/**
 * 发送消息并流式接收 Agent 回合（SSE）。
 * 见 api.md `发送消息并流式接收 Agent 回合`。
 * 注意：此端点返回 SSE 流，常规 HTTP 封装不直接消费；调用方用 `transport/sse.ts`。
 * 这里仅提供 *不取流* 的能力：用于探测会话可发送。
 */
export interface SendMessageRequest {
  prompt: string
  attachments?: Array<{ type: 'PACKAGE_REFERENCE'; packageId: number } | { type: 'UPLOAD_IMAGE'; attachmentId: string }>
  packageBinding?: { packageId: number }
}

export interface HistoryMessage {
  id: string
  seq: number
  role: 'USER' | 'ASSISTANT' | 'TOOL' | 'ATTACHMENT' | 'SYSTEM'
  content: string
  toolCallId?: string
  metadata?: Record<string, unknown>
  createdAt: string
  isCompactionBoundary?: boolean
  isCompactionSummary?: boolean
  attachedPackageId?: string
}

export interface HistoryResponse {
  messages: HistoryMessage[]
  compressionMarkerCount: number
  total: number
}

export function getHistory(
  conversationId: string,
  params: { page?: number; size?: number; sinceSeq?: number } = {}
): Promise<HistoryResponse> {
  const q = new URLSearchParams()
  if (params.page !== undefined) q.set('page', String(params.page))
  if (params.size !== undefined) q.set('size', String(params.size))
  if (params.sinceSeq !== undefined) q.set('sinceSeq', String(params.sinceSeq))
  const qs = q.toString()
  return request<HistoryResponse>(
    `/api/conversations/${encodeURIComponent(conversationId)}/messages${qs ? `?${qs}` : ''}`
  )
}