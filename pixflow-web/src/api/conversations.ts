import { z } from 'zod'
import { request } from './client'

export const conversationSchema = z.strictObject({
  conversationId: z.string().min(1),
  title: z.string(),
  createdAt: z.string().min(1),
  updatedAt: z.string().min(1)
})

const conversationPageSchema = z.strictObject({
  records: z.array(conversationSchema),
  total: z.number().int().nonnegative(),
  page: z.number().int().positive(),
  size: z.number().int().positive()
})

export type ConversationSummary = z.infer<typeof conversationSchema>
export type ConversationDetail = ConversationSummary

export async function createConversation(payload?: { title?: string }): Promise<ConversationDetail> {
  const raw = await request<unknown>('/api/conversations', {
    method: 'POST',
    body: payload ?? {},
    noRetry: true
  })
  return conversationSchema.parse(raw)
}

export async function listConversations(
  params: { page?: number; size?: number } = {}
): Promise<z.infer<typeof conversationPageSchema>> {
  const query = new URLSearchParams()
  if (params.page !== undefined) query.set('page', String(params.page))
  if (params.size !== undefined) query.set('size', String(params.size))
  const suffix = query.size > 0 ? `?${query.toString()}` : ''
  return conversationPageSchema.parse(await request<unknown>(`/api/conversations${suffix}`))
}

export async function getConversation(conversationId: string): Promise<ConversationDetail> {
  const raw = await request<unknown>(`/api/conversations/${encodeURIComponent(conversationId)}`)
  return conversationSchema.parse(raw)
}

export async function deleteConversation(conversationId: string): Promise<void> {
  await request<unknown>(`/api/conversations/${encodeURIComponent(conversationId)}`, {
    method: 'DELETE',
    noRetry: true
  })
}
