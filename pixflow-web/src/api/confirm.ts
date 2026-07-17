import { request } from './client'

export interface SubmitResponse {
  proposalId: string
  taskId: string
  status: string
}

export function confirm(conversationId: string, proposalId: string): Promise<SubmitResponse> {
  return request<SubmitResponse>(
    `/api/conversations/${encodeURIComponent(conversationId)}/proposals/${encodeURIComponent(proposalId)}/confirm`,
    { method: 'POST', body: {} }
  )
}

export function reject(conversationId: string, proposalId: string): Promise<void> {
  return request<void>(
    `/api/conversations/${encodeURIComponent(conversationId)}/proposals/${encodeURIComponent(proposalId)}/reject`,
    { method: 'POST', body: {} }
  )
}
