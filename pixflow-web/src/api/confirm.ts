import { request } from './client'
import type { ChallengeOrToken } from '@/types/agent'

/**
 * HITL 二次确认端点。
 * 见 web.md §十二。
 * 复用 /challenge：第一次无 body 拿 challenge，第二次带 answer 拿 token。
 */
export function challenge(conversationId: string, proposalId: string, body?: { answer: string }): Promise<ChallengeOrToken> {
  return request<ChallengeOrToken>(
    `/api/conversations/${encodeURIComponent(conversationId)}/confirm/${encodeURIComponent(proposalId)}/challenge`,
    { method: 'POST', body: body ?? {} }
  )
}

export interface SubmitResponse {
  taskId: string
  status: 'PENDING' | 'RUNNING'
}

export function submit(
  conversationId: string,
  proposalId: string,
  body: { challengeAnswer?: string },
  idempotencyKey: string
): Promise<SubmitResponse> {
  return request<SubmitResponse>(
    `/api/conversations/${encodeURIComponent(conversationId)}/confirm/${encodeURIComponent(proposalId)}/submit`,
    { method: 'POST', body, idempotencyKey, noRetry: true }
  )
}