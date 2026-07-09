import { request } from './client'
import type { ChallengeOrToken } from '@/types/agent'

/**
 * HITL 二次确认端点。
 * 见 web.md §十二。
 * 后端 /challenge 不读取 body；用户答案必须在 /submit 阶段提交。
 */
export function challenge(conversationId: string, proposalId: string): Promise<ChallengeOrToken> {
  return request<ChallengeOrToken>(
    `/api/conversations/${encodeURIComponent(conversationId)}/confirm/${encodeURIComponent(proposalId)}/challenge`,
    { method: 'POST', body: {} }
  )
}

export interface SubmitResponse {
  proposalId: string
  taskId: string
  status: string
}

export function submit(
  conversationId: string,
  proposalId: string,
  body: { challengeId?: string; challengeAnswer?: string },
  idempotencyKey: string
): Promise<SubmitResponse> {
  return request<SubmitResponse>(
    `/api/conversations/${encodeURIComponent(conversationId)}/confirm/${encodeURIComponent(proposalId)}/submit`,
    { method: 'POST', body, idempotencyKey, noRetry: true }
  )
}
