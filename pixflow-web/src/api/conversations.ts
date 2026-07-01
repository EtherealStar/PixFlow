import { request } from './client'
import type { Page } from '@/types/api'

export interface ConversationSummary {
  conversationId: string
  title?: string
  updatedAt: string
  packageId?: number | null
}

export interface ConversationDetail {
  conversationId: string
  title?: string
  createdAt: string
  updatedAt: string
  packageId?: number | null
}

export function createConversation(payload?: { title?: string; packageId?: number }): Promise<ConversationDetail> {
  return request<ConversationDetail>('/api/conversations', {
    method: 'POST',
    body: payload ?? {}
  })
}

export function listConversations(params: { archived?: boolean; page?: number; size?: number } = {}): Promise<Page<ConversationSummary>> {
  const q = new URLSearchParams()
  if (params.archived !== undefined) q.set('archived', String(params.archived))
  if (params.page !== undefined) q.set('page', String(params.page))
  if (params.size !== undefined) q.set('size', String(params.size))
  const qs = q.toString()
  return request<Page<ConversationSummary>>(`/api/conversations${qs ? `?${qs}` : ''}`)
}

export function getConversation(conversationId: string): Promise<ConversationDetail> {
  return request<ConversationDetail>(`/api/conversations/${encodeURIComponent(conversationId)}`)
}

export function archiveConversation(conversationId: string): Promise<void> {
  return request<void>(`/api/conversations/${encodeURIComponent(conversationId)}`, { method: 'DELETE' })
}