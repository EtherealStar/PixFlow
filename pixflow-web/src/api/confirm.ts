import { request } from './client'
import { z } from 'zod'

export interface SubmitResponse {
  proposalId: string
  taskId: string
  status: string
}

const submitResponseSchema = z.strictObject({
  proposalId: z.string().min(1),
  taskId: z.string().min(1),
  status: z.literal('CONFIRMED')
})

export async function confirm(conversationId: string, proposalId: string): Promise<SubmitResponse> {
  return submitResponseSchema.parse(await request<unknown>(
    `/api/conversations/${encodeURIComponent(conversationId)}/proposals/${encodeURIComponent(proposalId)}/confirm`,
    { method: 'POST', noRetry: true }
  ))
}

export async function reject(conversationId: string, proposalId: string): Promise<void> {
  await request<unknown>(
    `/api/conversations/${encodeURIComponent(conversationId)}/proposals/${encodeURIComponent(proposalId)}/reject`,
    { method: 'POST', noRetry: true }
  )
}
