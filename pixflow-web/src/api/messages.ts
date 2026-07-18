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
  references: MessageReference[]
}

export interface MessageReference {
  referenceKey: string
  displayPathSnapshot: string
}

export interface HistoryMessage {
  id: string
  seq: number
  role: 'USER' | 'ASSISTANT' | 'TOOL_RESULT'
  content: string
  toolCallId?: string
  references: MessageReference[]
  createdAt: string
  isCompactionBoundary?: boolean
  isCompactionSummary?: boolean
}

interface BackendMessageView {
  id: string
  seq: number
  role: HistoryMessage['role']
  content: string
  toolCallId?: string | null
  references?: MessageReference[] | null
  createdAt: string
  compactionMarker?: string | null
  compactionBoundary?: boolean | null
  taskId?: string | null
}

export type HistoryResponse = Page<HistoryMessage>

function normalizeMessage(raw: BackendMessageView): HistoryMessage {
  return {
    id: String(raw.id),
    seq: raw.seq,
    role: raw.role,
    content: raw.content,
    toolCallId: raw.toolCallId ?? undefined,
    references: raw.references ?? [],
    createdAt: raw.createdAt,
    isCompactionBoundary: Boolean(raw.compactionBoundary),
    isCompactionSummary: raw.compactionMarker === 'SUMMARY'
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
