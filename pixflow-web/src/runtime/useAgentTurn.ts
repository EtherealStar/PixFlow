import { ref, type Ref } from 'vue'
import { z } from 'zod'
import { createSseClient } from '@/transport/sse'
import { submit as submitConfirm } from '@/api/confirm'
import { getIdempotencyKey, setIdempotencyKey } from '@/utils/idempotencyStore'
import { newId, newUuid } from '@/utils/id'
import type { ApiError } from '@/types/api'
import type {
  AgentTurnPhase,
  AgentTurnSummary,
  AssistantDeltaPayload,
  ChallengeOrToken,
  CompletedPayload,
  ErrorEventPayload,
  Proposal,
  ToolCallReadyPayload,
  ToolResultPayload,
  ToolStartedPayload,
  TransitionPayload
} from '@/types/agent'

/**
 * Agent 回合状态机。
 * 见 web.md §八。
 *
 *  - deltas 维护在 composable 内的 ref<string>（不入 Pinia）
 *  - confirmationToken 永不出函数（仅在闭包内流转）
 *  - 多回合并发：同 conversationId 不允许两个 active 回合
 */

interface PendingProposal {
  proposal: Proposal
  /** challenge 暂存（needChallenge=true 时） */
  challengeId?: string
  /** token 暂存（needChallenge=false 或答对 challenge 后） */
  token?: string
}

export interface SendAttachments {
  attachments?: Array<{ type: 'PACKAGE_REFERENCE'; packageId: number } | { type: 'UPLOAD_IMAGE'; attachmentId: string }>
  packageBinding?: { packageId: number }
}

export function createAgentTurn(opts: { conversationId: string }) {
  const phase = ref<AgentTurnPhase>('idle')
  const summary: Ref<AgentTurnSummary> = ref({
    conversationId: opts.conversationId,
    phase: 'idle'
  })
  const deltas = ref<string>('')
  const proposals = ref<Proposal[]>([])

  let abortController: AbortController | null = null
  let pending: PendingProposal | null = null

  function setPhase(p: AgentTurnPhase, partial: Partial<AgentTurnSummary> = {}): void {
    phase.value = p
    summary.value = { ...summary.value, phase: p, ...partial }
  }

  function abort(): void {
    if (abortController) {
      abortController.abort()
      abortController = null
    }
    if (phase.value !== 'idle' && phase.value !== 'completed') {
      setPhase('cancelled')
    }
  }

  async function send(prompt: string, attachments: SendAttachments = {}): Promise<void> {
    if (phase.value === 'sending' || phase.value === 'streaming') {
      // 已有 active 回合 → abort
      abort()
    }
    deltas.value = ''
    proposals.value = []
    setPhase('sending')

    abortController = new AbortController()
    const body = { prompt, ...attachments }
    let sse: { close: () => void } | null = null
    let receivedCompletion = false

    setPhase('streaming')
    sse = createSseClient({
      url: `/api/conversations/${encodeURIComponent(opts.conversationId)}/messages`,
      method: 'POST',
      body,
      signal: abortController.signal,
      onEvent: (ev) => {
        switch (ev.name) {
          case 'assistant_delta': {
            const p = ev.data as AssistantDeltaPayload
            deltas.value += p.text ?? ''
            break
          }
          case 'tool_call_ready': {
            const p = ev.data as ToolCallReadyPayload
            // 工具调用：可记录到 UI；本计划不展开工具 UI
            if (__DEV__) console.debug('[agent] tool_call_ready', p.toolName, p.toolCallId)
            break
          }
          case 'tool_started': {
            const p = ev.data as ToolStartedPayload
            if (__DEV__) console.debug('[agent] tool_started', p.toolCallId)
            break
          }
          case 'tool_result': {
            const p = ev.data as ToolResultPayload
            if (__DEV__) console.debug('[agent] tool_result', p.toolCallId, p.externalized)
            break
          }
          case 'transition': {
            const p = ev.data as TransitionPayload
            if (__DEV__) console.debug('[agent] transition', p.reason)
            break
          }
          case 'completed': {
            const p = ev.data as CompletedPayload
            receivedCompletion = true
            setPhase('completed', {})
            if (p.traceId) summary.value.traceId = p.traceId
            break
          }
          case 'error': {
            const p = ev.data as ErrorEventPayload
            const err: ApiError = {
              status: 0,
              errorCode: p.errorCode ?? 'STREAM_ERROR',
              message: p.message ?? 'agent stream error',
              traceId: p.traceId ?? ''
            }
            setPhase('error', { error: err })
            break
          }
          default: {
            // unknown event name → ignore
            break
          }
        }
      },
      onError: (err) => {
        if (receivedCompletion) return
        setPhase('error', { error: err })
      },
      onClose: () => {
        if (phase.value === 'streaming' && !receivedCompletion) {
          setPhase('error', { error: { status: 0, errorCode: 'STREAM_INTERRUPTED', message: 'SSE 关闭前未收到 completed 事件', traceId: '' } })
        }
      }
    })

    // 等待流结束（phase 变 completed / error / cancelled 后退出）
    while (phase.value === 'streaming' || phase.value === 'sending') {
      await new Promise((r) => setTimeout(r, 50))
    }
    sse?.close()
  }

  function setProposal(p: Proposal): void {
    proposals.value = [...proposals.value, p]
    pending = { proposal: p }
  }

  function reject(proposalId: string): void {
    const idx = proposals.value.findIndex((p) => p.proposalId === proposalId)
    if (idx === -1) return
    const next = proposals.value.slice()
    const rejected = { ...next[idx]!, type: next[idx]!.type } as Proposal & { rejected?: true }
    ;(rejected as Proposal & { rejected?: true }).rejected = true
    next[idx] = rejected
    proposals.value = next
    if (pending?.proposal.proposalId === proposalId) {
      pending = null
    }
    // 不调 /submit；只把状态置 REJECTED
    setPhase('completed')
  }

  /**
   * HITL 三步编排（见 web.md §十二）。
   * @param proposalId 待确认提案
   * @param challengeAnswer 仅在 needChallenge=true 时需要回答
   */
  async function confirm(proposalId: string, challengeAnswer?: string): Promise<{ taskId: string }> {
    if (!pending || pending.proposal.proposalId !== proposalId) {
      throw {
        status: 400,
        errorCode: 'PROPOSAL_NOT_FOUND',
        message: '本地无对应 proposal 状态',
        traceId: ''
      } as ApiError
    }
    setPhase('awaiting_challenge')
    let ct: ChallengeOrToken
    try {
      ct = await import('@/api/confirm').then((m) => m.challenge(opts.conversationId, proposalId, challengeAnswer ? { answer: challengeAnswer } : undefined))
    } catch (e) {
      const err = e as ApiError
      if (err.errorCode === 'PROPOSAL_CHALLENGE_FAILED') {
        setPhase('awaiting_challenge', { error: err })
        throw err
      }
      if (err.errorCode === 'PROPOSAL_CHALLENGE_EXPIRED') {
        setPhase('error', { error: err })
        throw err
      }
      throw err
    }
    if (ct.needChallenge) {
      if (!ct.challenge) {
        const err: ApiError = { status: 500, errorCode: 'INTERNAL_ERROR', message: 'challenge 响应缺 challenge 字段', traceId: '' }
        setPhase('error', { error: err })
        throw err
      }
      pending.challengeId = ct.challenge.challengeId
      // 等用户在 UI 输入答案（ChallengeDialog 调 confirm(proposalId, answer) 再走一次）
      return { taskId: '' }
    }
    if (!ct.token) {
      const err: ApiError = { status: 500, errorCode: 'INTERNAL_ERROR', message: 'challenge 响应缺 token 字段', traceId: '' }
      setPhase('error', { error: err })
      throw err
    }
    pending.token = ct.token.token
    return await doSubmit(proposalId)
  }

  async function doSubmit(proposalId: string): Promise<{ taskId: string }> {
    if (!pending || !pending.token) {
      throw {
        status: 400,
        errorCode: 'CONFIRMATION_TOKEN_INVALID',
        message: 'no token to submit',
        traceId: ''
      } as ApiError
    }
    setPhase('awaiting_confirm')
    const idemp = getIdempotencyKey(proposalId) ?? newUuid()
    setIdempotencyKey(proposalId, idemp)
    try {
      const r = await submitConfirm(
        opts.conversationId,
        proposalId,
        { challengeAnswer: undefined },
        idemp
      )
      setPhase('completed', { taskId: r.taskId })
      pending = null
      return { taskId: r.taskId }
    } catch (e) {
      const err = e as ApiError
      if (err.errorCode === 'PROPOSAL_ALREADY_CONFIRMED') {
        // 幂等：视为成功
        setPhase('completed')
        return { taskId: err.details?.taskId as string ?? '' }
      }
      if (err.errorCode === 'CONFIRMATION_TOKEN_INVALID') {
        setPhase('error', { error: err })
        throw err
      }
      throw err
    }
  }

  return {
    state: summary,
    phase,
    deltas,
    proposals,
    send,
    confirm,
    reject,
    abort,
    setProposal,
    /** 测试用：注入 proposal 状态 */
    __setPendingProposal: (p: Proposal) => setProposal(p)
  }
}

export type AgentTurn = ReturnType<typeof createAgentTurn>

/** 工具：从 SSE 事件负载 schema 校验（仅供测试 / 调试）。 */
export const sseEventDataSchemas = {
  assistant_delta: z.object({ text: z.string() }),
  tool_call_ready: z.object({ toolName: z.string(), toolCallId: z.string(), toolInput: z.record(z.string(), z.unknown()) }),
  tool_started: z.object({ toolCallId: z.string() }),
  tool_result: z.object({ toolCallId: z.string(), content: z.string(), externalized: z.boolean() }),
  transition: z.object({ reason: z.string() }),
  completed: z.object({ finalText: z.string(), traceId: z.string(), turnNo: z.number() }),
  error: z.object({ errorCode: z.string(), message: z.string(), traceId: z.string() })
}

/** 内部使用：标记 proposal 接受（同 useAgentTurn.rejected 列表）。 */
export function isRejected(p: Proposal & { rejected?: boolean }): boolean {
  return Boolean(p.rejected)
}

/** 工具：导出 job id 生成（测试用）。 */
export function generateJobId(): string { return newId() }