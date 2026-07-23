import { z } from 'zod'
import { request } from './client'

export const messageReferenceSchema = z.strictObject({
  referenceKey: z.string().min(1),
  displayPathSnapshot: z.string()
})

export const historyMessageSchema = z.strictObject({
  messageId: z.string().min(1),
  seq: z.number().int().nonnegative(),
  role: z.enum(['USER', 'ASSISTANT']),
  content: z.string(),
  references: z.array(messageReferenceSchema),
  createdAt: z.string().min(1)
})

const historyPageSchema = z.strictObject({
  records: z.array(historyMessageSchema),
  total: z.number().int().nonnegative(),
  page: z.number().int().positive(),
  size: z.number().int().positive()
})

export interface MessageReference {
  referenceKey: string
  displayPathSnapshot: string
}

export type HistoryMessage = z.infer<typeof historyMessageSchema>
export type HistoryResponse = z.infer<typeof historyPageSchema>

export async function getHistory(
  conversationId: string,
  params: { page?: number; size?: number } = {}
): Promise<HistoryResponse> {
  const query = new URLSearchParams({ page: String(params.page ?? 1) })
  if (params.size !== undefined) query.set('size', String(params.size))
  const raw = await request<unknown>(
    `/api/conversations/${encodeURIComponent(conversationId)}/messages?${query.toString()}`
  )
  return historyPageSchema.parse(raw)
}

export async function stopTurn(conversationId: string): Promise<void> {
  await request<unknown>(`/api/conversations/${encodeURIComponent(conversationId)}/turns/stop`, {
    method: 'POST',
    noRetry: true
  })
}
