import { request } from './client'
import type { Page } from '@/types/api'

/**
 * 发送消息并流式接收 Agent 回合（SSE）。
 * 见 api.md `发送消息并流式接收 Agent 回合`。
 * 注意：此端点返回 SSE 流，常规 HTTP 封装不直接消费；调用方用 `transport/sse.ts`。
 * 这里仅提供 *不取流* 的能力：用于探测会话可发送。
 */
export interface SendMessageRequest {
  prompt?: string
  attachments?: Array<{ type: 'UPLOAD_IMAGE'; attachmentId?: string; sourceRef: string; metadata?: Record<string, unknown> | null }>
  packageId?: string
  metadata?: Record<string, unknown>
}

export interface HistoryMessage {
  id: string
  seq: number
  role: 'USER' | 'ASSISTANT' | 'TOOL' | 'ATTACHMENT' | 'SYSTEM'
  content: string
  toolCallId?: string
  metadata?: Record<string, unknown>
  metadataRaw?: string
  createdAt: string
  isCompactionBoundary?: boolean
  isCompactionSummary?: boolean
  attachedPackageId?: string
}

interface BackendMessageView {
  id: string
  seq: number
  role: HistoryMessage['role']
  content: string
  toolCallId?: string | null
  metadata?: string | Record<string, unknown> | null
  createdAt: string
  compactionMarker?: string | null
  compactionBoundary?: boolean | null
  attachedPackageId?: string | number | null
  taskId?: string | null
}

export type HistoryResponse = Page<HistoryMessage>

function parseMetadata(raw: BackendMessageView['metadata']): Pick<HistoryMessage, 'metadata' | 'metadataRaw'> {
  if (raw == null) return {}
  if (typeof raw === 'object') return { metadata: raw }
  try {
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object'
      ? { metadata: parsed as Record<string, unknown> }
      : { metadataRaw: raw }
  } catch {
    return { metadataRaw: raw }
  }
}

function normalizeMessage(raw: BackendMessageView): HistoryMessage {
  const metadata = parseMetadata(raw.metadata)
  return {
    id: String(raw.id),
    seq: raw.seq,
    role: raw.role,
    content: raw.content,
    toolCallId: raw.toolCallId ?? undefined,
    ...metadata,
    createdAt: raw.createdAt,
    isCompactionBoundary: Boolean(raw.compactionBoundary),
    isCompactionSummary: raw.compactionMarker === 'SUMMARY',
    attachedPackageId: raw.attachedPackageId == null ? undefined : String(raw.attachedPackageId)
  }
}

function normalizeHistoryPage(page: Page<BackendMessageView>): Page<HistoryMessage> {
  const items = (page.items ?? page.records ?? []).map(normalizeMessage)
  return { ...page, items, records: items }
}

export async function getHistory(
  conversationId: string,
  params: { page?: number; size?: number } = {}
): Promise<Page<HistoryMessage>> {
  const q = new URLSearchParams()
  q.set('page', String(params.page ?? 1))
  if (params.size !== undefined) q.set('size', String(params.size))
  const qs = q.toString()
  const page = await request<Page<BackendMessageView>>(
    `/api/conversations/${encodeURIComponent(conversationId)}/messages${qs ? `?${qs}` : ''}`
  )
  return normalizeHistoryPage(page)
}
