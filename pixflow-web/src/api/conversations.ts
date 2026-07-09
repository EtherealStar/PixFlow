import { request } from './client'
import type { Page } from '@/types/api'
import type { ImageItem } from '@/types/upload'

export interface ConversationSummary {
  conversationId: string
  title?: string
  updatedAt: string
  packageId?: string | null
  images?: ImageItem[]
}

export interface ConversationDetail {
  conversationId: string
  title?: string
  createdAt: string
  updatedAt: string
  packageId?: string | null
  images?: ImageItem[]
}

interface BackendConversationView {
  id?: string | number
  conversationId?: string | number
  title?: string
  createdAt?: string
  updatedAt?: string
  packageId?: string | number | null
  images?: ImageItem[]
}

function normalizeConversation(raw: BackendConversationView): ConversationDetail {
  // 后端真实字段是 id；前端统一消费 conversationId，避免页面层兼容两套字段。
  const conversationId = String(raw.conversationId ?? raw.id ?? '')
  return {
    conversationId,
    title: raw.title,
    createdAt: raw.createdAt ?? '',
    updatedAt: raw.updatedAt ?? '',
    packageId: raw.packageId == null ? null : String(raw.packageId),
    images: raw.images
  }
}

function normalizeConversationPage(page: Page<BackendConversationView>): Page<ConversationSummary> {
  const items = (page.items ?? page.records ?? []).map(normalizeConversation)
  return { ...page, items, records: items }
}

export async function createConversation(payload?: { title?: string; packageId?: string | number }): Promise<ConversationDetail> {
  const raw = await request<BackendConversationView>('/api/conversations', {
    method: 'POST',
    body: payload ?? {}
  })
  return normalizeConversation(raw)
}

export async function listConversations(
  params: { includeArchived?: boolean; archived?: boolean; page?: number; size?: number } = {}
): Promise<Page<ConversationSummary>> {
  const q = new URLSearchParams()
  const includeArchived = params.includeArchived ?? params.archived
  if (includeArchived !== undefined) q.set('includeArchived', String(includeArchived))
  if (params.page !== undefined) q.set('page', String(params.page))
  if (params.size !== undefined) q.set('size', String(params.size))
  const qs = q.toString()
  const page = await request<Page<BackendConversationView>>(`/api/conversations${qs ? `?${qs}` : ''}`)
  return normalizeConversationPage(page)
}

export async function getConversation(conversationId: string): Promise<ConversationDetail> {
  const raw = await request<BackendConversationView>(`/api/conversations/${encodeURIComponent(conversationId)}`)
  return normalizeConversation(raw)
}

export function archiveConversation(conversationId: string): Promise<void> {
  return request<void>(`/api/conversations/${encodeURIComponent(conversationId)}`, { method: 'DELETE' })
}
