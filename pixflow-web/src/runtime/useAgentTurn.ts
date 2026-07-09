import { computed, ref, type Ref } from 'vue'
import { z } from 'zod'
import { createSseClient } from '@/transport/sse'
import { challenge as challengeConfirm, submit as submitConfirm } from '@/api/confirm'
import { getIdempotencyKey, setIdempotencyKey } from '@/utils/idempotencyStore'
import { newId, newUuid } from '@/utils/id'
import type { ApiError } from '@/types/api'
import type {
  AgentEvent,
  AgentEventAttribution,
  AgentTurnPhase,
  AgentTurnSummary,
  AssistantDeltaPayload,
  AssistantMessageCompletedPayload,
  ChallengeOrToken,
  ConfirmationChallenge,
  CompletedPayload,
  ErrorEventPayload,
  Proposal,
  TimelineItem,
  ToolCallReadyPayload,
  ToolResultPayload,
  ToolStartedPayload,
  TransitionPayload
} from '@/types/agent'

/**
 * Agent 回合状态机。
 * 见 web.md §八。
 *
 *  - timeline 维护在 composable 内的 ref<TimelineItem[]>（不入 Pinia）
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

interface QueuedTurn {
  prompt: string
  attachments: SendAttachments
  resolve: () => void
  reject: (error: ApiError) => void
}

export interface SendAttachments {
  attachments?: Array<{ type: 'UPLOAD_IMAGE'; attachmentId?: string; sourceRef: string; metadata?: Record<string, unknown> | null }>
  packageId?: string
}

export interface ConfirmResult {
  taskId: string
  challenge?: ConfirmationChallenge
}

export function createAgentTurn(opts: { conversationId: string }) {
  const phase = ref<AgentTurnPhase>('idle')
  const summary: Ref<AgentTurnSummary> = ref({
    conversationId: opts.conversationId,
    phase: 'idle'
  })
  const timeline = ref<TimelineItem[]>([])
  const proposals = ref<Proposal[]>([])
  const queuedTurns = ref<QueuedTurn[]>([])
  const queuedCount = computed(() => queuedTurns.value.length)

  let localAssistantSeq = 0
  let localTimelineSeq = 0
  let activeAssistantItemId: string | null = null
  let lastAssistantCallId: string | null = null
  let abortController: AbortController | null = null
  let activeSse: { close: () => void } | null = null
  let activeTurn: Promise<void> | null = null
  let failActiveTurn: ((error: ApiError) => void) | null = null
  let pending: PendingProposal | null = null

  function setPhase(p: AgentTurnPhase, partial: Partial<AgentTurnSummary> = {}): void {
    phase.value = p
    summary.value = { ...summary.value, phase: p, queuedCount: queuedTurns.value.length, ...partial }
  }

  function abort(): void {
    const err: ApiError = {
      status: 0,
      errorCode: 'TURN_CANCELLED',
      message: 'Agent 回合已取消',
      traceId: ''
    }
    if (abortController) {
      abortController.abort()
      abortController = null
    }
    rejectQueuedTurns(err)
    failActiveTurn?.(err)
    activeSse?.close()
    activeSse = null
    if (phase.value !== 'idle' && phase.value !== 'completed') {
      setPhase('cancelled')
    }
  }

  async function send(prompt: string, attachments: SendAttachments = {}): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const item: QueuedTurn = { prompt, attachments, resolve, reject }
      if (activeTurn || isActivePhase(phase.value)) {
        queuedTurns.value = [...queuedTurns.value, item]
        updateQueuedCount()
        return
      }
      startQueuedTurn(item)
    })
  }

  function startQueuedTurn(item: QueuedTurn): void {
    const turn = runSingleTurn(item.prompt, item.attachments)
    activeTurn = turn
    turn
      .then(() => {
        item.resolve()
        activeTurn = null
        startNextQueuedTurn()
      })
      .catch((error: ApiError | Error | unknown) => {
        const apiError = toApiError(error)
        item.reject(apiError)
        activeTurn = null
        rejectQueuedTurns(apiError)
      })
  }

  function startNextQueuedTurn(): void {
    if (activeTurn || queuedTurns.value.length === 0 || phase.value === 'error' || phase.value === 'cancelled') return
    const [next, ...rest] = queuedTurns.value
    queuedTurns.value = rest
    updateQueuedCount()
    if (next) startQueuedTurn(next)
  }

  function rejectQueuedTurns(error: ApiError): void {
    const queued = queuedTurns.value
    queuedTurns.value = []
    updateQueuedCount()
    queued.forEach((item) => item.reject(error))
  }

  function updateQueuedCount(): void {
    summary.value = { ...summary.value, queuedCount: queuedTurns.value.length }
  }

  async function runSingleTurn(prompt: string, attachments: SendAttachments = {}): Promise<void> {
    resetTimeline()
    proposals.value = []
    summary.value = {
      conversationId: opts.conversationId,
      phase: 'sending',
      queuedCount: queuedTurns.value.length
    }
    setPhase('sending')

    const controller = new AbortController()
    abortController = controller
    const body = { prompt, ...attachments }
    let sse: { close: () => void } | null = null
    let receivedCompletion = false

    return await new Promise<void>((resolve, reject) => {
      let settled = false

      function cleanup(): void {
        if (activeSse === sse) activeSse = null
        if (abortController === controller) abortController = null
        if (failActiveTurn === fail) failActiveTurn = null
        sse?.close()
      }

      function succeed(): void {
        if (settled) return
        settled = true
        cleanup()
        resolve()
      }

      function fail(error: ApiError): void {
        if (settled) return
        settled = true
        cleanup()
        reject(error)
      }

      failActiveTurn = fail
      setPhase('streaming')
      sse = createSseClient({
        url: `/api/conversations/${encodeURIComponent(opts.conversationId)}/messages`,
        method: 'POST',
        body,
        signal: controller.signal,
        onEvent: (ev) => {
          reduceAgentEvent(ev as AgentEvent)
          switch (ev.name) {
            case 'assistant_delta': {
              break
            }
            case 'assistant_message_completed': {
              const p = ev.data as AssistantMessageCompletedPayload
              // 后端会单独发消息完成事件；只记录可用元数据，不把它当成终态。
              if (p.traceId) summary.value.traceId = p.traceId
              break
            }
            case 'tool_call_ready': {
              break
            }
            case 'tool_started': {
              break
            }
            case 'tool_result': {
              break
            }
            case 'transition': {
              break
            }
            case 'completed': {
              const p = ev.data as CompletedPayload
              receivedCompletion = true
              setPhase('completed', {})
              if (p.traceId) summary.value.traceId = p.traceId
              succeed()
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
              fail(err)
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
          const apiError = toApiError(err)
          setPhase('error', { error: apiError })
          fail(apiError)
        },
        onClose: () => {
          if (settled || receivedCompletion) return
          const err: ApiError = { status: 0, errorCode: 'STREAM_INTERRUPTED', message: 'SSE 关闭前未收到 completed 事件', traceId: '' }
          setPhase('error', { error: err })
          fail(err)
        }
      })
      activeSse = sse
    })
  }

  function resetTimeline(): void {
    timeline.value = []
    localAssistantSeq = 0
    localTimelineSeq = 0
    activeAssistantItemId = null
    lastAssistantCallId = null
  }

  function reduceAgentEvent(event: AgentEvent): void {
    switch (event.name) {
      case 'assistant_delta':
        appendAssistantDelta(event.data as AssistantDeltaPayload)
        break
      case 'assistant_message_completed':
        completeAssistant(event.data as AssistantMessageCompletedPayload)
        break
      case 'tool_call_ready':
        upsertTool(event.data as ToolCallReadyPayload, 'queued')
        break
      case 'tool_started':
        upsertTool(event.data as ToolStartedPayload, 'running')
        break
      case 'tool_result':
        applyToolResult(event.data as ToolResultPayload)
        break
      case 'transition':
        applyTransitionEvent(event.data as TransitionPayload)
        break
      case 'error':
        appendError(event.data as ErrorEventPayload)
        break
      case 'completed':
        markStreamingAssistantCompleted()
        break
    }
  }

  function appendAssistantDelta(payload: AssistantDeltaPayload): void {
    const attribution = resolveAttribution(payload)
    const existingIndex = findAssistantIndex(attribution.assistantCallId)
    if (existingIndex >= 0) {
      const item = timeline.value[existingIndex]
      if (item?.type !== 'assistant') return
      const next = [...timeline.value]
      next[existingIndex] = { ...item, text: item.text + (payload.text ?? ''), status: 'streaming' }
      timeline.value = next
      activeAssistantItemId = item.id
      lastAssistantCallId = item.assistantCallId
      return
    }

    const item: TimelineItem = {
      id: nextTimelineId('assistant'),
      type: 'assistant',
      assistantCallId: attribution.assistantCallId,
      modelTurnIndex: attribution.modelTurnIndex,
      text: payload.text ?? '',
      status: 'streaming',
      traceId: payload.traceId,
      turnNo: payload.turnNo
    }
    timeline.value = [...timeline.value, item]
    activeAssistantItemId = item.id
    lastAssistantCallId = item.assistantCallId
  }

  function completeAssistant(payload: AssistantMessageCompletedPayload): void {
    const attribution = resolveAttribution(payload)
    let index = findAssistantIndex(attribution.assistantCallId)
    if (index < 0 && activeAssistantItemId) {
      index = timeline.value.findIndex((item) => item.type === 'assistant' && item.id === activeAssistantItemId)
    }
    if (index < 0) {
      const item: TimelineItem = {
        id: nextTimelineId('assistant'),
        type: 'assistant',
        assistantCallId: attribution.assistantCallId,
        modelTurnIndex: attribution.modelTurnIndex,
        text: payload.finalText ?? '',
        status: 'completed',
        messageId: payload.messageId,
        traceId: payload.traceId,
        turnNo: payload.turnNo
      }
      timeline.value = [...timeline.value, item]
      lastAssistantCallId = item.assistantCallId
      activeAssistantItemId = null
      return
    }

    const item = timeline.value[index]
    if (item?.type !== 'assistant') return
    const next = [...timeline.value]
    next[index] = {
      ...item,
      text: item.text || payload.finalText || '',
      status: 'completed',
      messageId: payload.messageId,
      traceId: payload.traceId ?? item.traceId,
      turnNo: payload.turnNo ?? item.turnNo
    }
    timeline.value = next
    lastAssistantCallId = item.assistantCallId
    activeAssistantItemId = null
  }

  function upsertTool(payload: ToolCallReadyPayload | ToolStartedPayload, status: 'queued' | 'running'): void {
    const attribution = resolveAttribution(payload)
    const toolCallId = payload.toolCallId || nextTimelineId('tool-call')
    const index = timeline.value.findIndex((item) => item.type === 'tool' && item.toolCallId === toolCallId)
    if (index >= 0) {
      const item = timeline.value[index]
      if (item?.type !== 'tool') return
      const next = [...timeline.value]
      next[index] = {
        ...item,
        status,
        toolName: 'toolName' in payload && payload.toolName ? payload.toolName : item.toolName,
        input: 'toolInput' in payload && payload.toolInput !== undefined ? payload.toolInput : item.input,
        traceId: payload.traceId ?? item.traceId,
        turnNo: payload.turnNo ?? item.turnNo
      }
      timeline.value = next
      return
    }

    const toolName = 'toolName' in payload && payload.toolName ? payload.toolName : 'tool'
    const input = 'toolInput' in payload ? payload.toolInput : undefined
    const item: TimelineItem = {
      id: nextTimelineId('tool'),
      type: 'tool',
      assistantCallId: attribution.assistantCallId,
      modelTurnIndex: attribution.modelTurnIndex,
      toolCallId,
      toolName,
      input,
      status,
      traceId: payload.traceId,
      turnNo: payload.turnNo
    }
    timeline.value = [...timeline.value, item]
  }

  function applyToolResult(payload: ToolResultPayload): void {
    const attribution = resolveAttribution(payload)
    const index = timeline.value.findIndex((item) => item.type === 'tool' && item.toolCallId === payload.toolCallId)
    const status = payload.error ? 'error' : 'completed'
    if (index >= 0) {
      const item = timeline.value[index]
      if (item?.type !== 'tool') return
      const next = [...timeline.value]
      next[index] = {
        ...item,
        status,
        toolName: payload.toolName || item.toolName,
        result: payload.content ?? '',
        metadata: payload.metadata,
        externalized: payload.externalized,
        traceId: payload.traceId ?? item.traceId,
        turnNo: payload.turnNo ?? item.turnNo
      }
      timeline.value = next
      return
    }

    timeline.value = [...timeline.value, {
      id: nextTimelineId('tool'),
      type: 'tool',
      assistantCallId: attribution.assistantCallId,
      modelTurnIndex: attribution.modelTurnIndex,
      toolCallId: payload.toolCallId,
      toolName: payload.toolName || 'tool',
      result: payload.content ?? '',
      metadata: payload.metadata,
      status,
      externalized: payload.externalized,
      traceId: payload.traceId,
      turnNo: payload.turnNo
    }]
  }

  function applyTransitionEvent(payload: TransitionPayload): void {
    if (payload.reason === 'TOOL_USE') {
      // 工具续轮后下一段 assistant_delta 必须新建 assistant item，避免多轮文本混到同一条。
      activeAssistantItemId = null
      lastAssistantCallId = null
      return
    }
    if (payload.reason === 'RATE_LIMIT_RETRY') {
      const attribution = resolveAttribution(payload)
      const index = timeline.value.findIndex((item) =>
        item.type === 'transition' &&
        item.reason === 'RATE_LIMIT_RETRY' &&
        item.assistantCallId === attribution.assistantCallId
      )
      const retryItem: TimelineItem = {
        id: index >= 0 ? timeline.value[index]!.id : nextTimelineId('transition'),
        type: 'transition',
        reason: payload.reason,
        assistantCallId: attribution.assistantCallId,
        modelTurnIndex: attribution.modelTurnIndex,
        attempt: payload.attempt,
        retriesRemaining: payload.retriesRemaining,
        errorCode: payload.errorCode,
        message: payload.message,
        retrying: payload.retrying ?? true,
        traceId: payload.traceId,
        turnNo: payload.turnNo
      }
      // RATE_LIMIT_RETRY 是非终态提示，不能把当前回合切到 error。
      if (index >= 0) {
        const next = [...timeline.value]
        next[index] = retryItem
        timeline.value = next
      } else {
        timeline.value = [...timeline.value, retryItem]
      }
    }
  }

  function appendError(payload: ErrorEventPayload): void {
    timeline.value = [...timeline.value, {
      id: nextTimelineId('error'),
      type: 'error',
      message: payload.message ?? 'agent stream error',
      errorCode: payload.errorCode,
      traceId: payload.traceId
    }]
  }

  function markStreamingAssistantCompleted(): void {
    if (!activeAssistantItemId) return
    const index = timeline.value.findIndex((item) => item.type === 'assistant' && item.id === activeAssistantItemId)
    if (index < 0) return
    const item = timeline.value[index]
    if (item?.type !== 'assistant') return
    const next = [...timeline.value]
    next[index] = { ...item, status: 'completed' }
    timeline.value = next
    activeAssistantItemId = null
  }

  function findAssistantIndex(assistantCallId: string): number {
    return timeline.value.findIndex((item) => item.type === 'assistant' && item.assistantCallId === assistantCallId)
  }

  function resolveAttribution(payload: AgentEventAttribution): { assistantCallId: string; modelTurnIndex: number } {
    const existing = payload.assistantCallId || lastAssistantCallId || assistantCallIdFromActive()
    const assistantCallId = existing || `local-assistant-${++localAssistantSeq}`
    const modelTurnIndex = typeof payload.modelTurnIndex === 'number' ? payload.modelTurnIndex : modelTurnIndexFromId(assistantCallId)
    return { assistantCallId, modelTurnIndex }
  }

  function assistantCallIdFromActive(): string | null {
    if (!activeAssistantItemId) return null
    const item = timeline.value.find((entry) => entry.type === 'assistant' && entry.id === activeAssistantItemId)
    return item?.type === 'assistant' ? item.assistantCallId : null
  }

  function modelTurnIndexFromId(assistantCallId: string): number {
    const item = timeline.value.find((entry) => entry.type === 'assistant' && entry.assistantCallId === assistantCallId)
    return item?.type === 'assistant' ? item.modelTurnIndex : localAssistantSeq || 1
  }

  function nextTimelineId(prefix: string): string {
    localTimelineSeq += 1
    return `${prefix}-${localTimelineSeq}`
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
  async function confirm(proposalId: string, challengeAnswer?: string): Promise<ConfirmResult> {
    if (!pending || pending.proposal.proposalId !== proposalId) {
      throw {
        status: 400,
        errorCode: 'PROPOSAL_NOT_FOUND',
        message: '本地无对应 proposal 状态',
        traceId: ''
      } as ApiError
    }
    if (challengeAnswer && pending.challengeId) {
      return await doSubmit(proposalId, challengeAnswer)
    }
    setPhase('awaiting_challenge')
    let ct: ChallengeOrToken
    try {
      ct = await challengeConfirm(opts.conversationId, proposalId)
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
      return { taskId: '', challenge: ct.challenge }
    }
    if (!ct.token) {
      const err: ApiError = { status: 500, errorCode: 'INTERNAL_ERROR', message: 'challenge 响应缺 token 字段', traceId: '' }
      setPhase('error', { error: err })
      throw err
    }
    pending.token = typeof ct.token === 'string' ? ct.token : ct.token.token
    return await doSubmit(proposalId)
  }

  async function doSubmit(proposalId: string, challengeAnswer?: string): Promise<ConfirmResult> {
    if (!pending || (!pending.token && !pending.challengeId)) {
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
        {
          challengeId: pending.challengeId,
          challengeAnswer
        },
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
    timeline,
    proposals,
    send,
    confirm,
    reject,
    abort,
    setProposal,
    queuedCount,
    /** 测试用：注入 proposal 状态 */
    __setPendingProposal: (p: Proposal) => setProposal(p)
  }
}

function isActivePhase(phase: AgentTurnPhase): boolean {
  return phase === 'sending' || phase === 'streaming' || phase === 'awaiting_challenge' || phase === 'awaiting_confirm'
}

function toApiError(error: ApiError | Error | unknown): ApiError {
  if (typeof error === 'object' && error !== null && 'errorCode' in error && 'message' in error) {
    return error as ApiError
  }
  if (error instanceof Error) {
    return {
      status: 0,
      errorCode: 'STREAM_ERROR',
      message: error.message,
      traceId: ''
    }
  }
  return {
    status: 0,
    errorCode: 'STREAM_ERROR',
    message: 'agent stream error',
    traceId: ''
  }
}

export type AgentTurn = ReturnType<typeof createAgentTurn>

/** 工具：从 SSE 事件负载 schema 校验（仅供测试 / 调试）。 */
const attributionSchema = {
  assistantCallId: z.string().optional(),
  modelTurnIndex: z.number().optional(),
  iteration: z.number().optional(),
  traceId: z.string().optional(),
  turnNo: z.number().optional()
}

export const sseEventDataSchemas = {
  assistant_delta: z.object({ ...attributionSchema, text: z.string() }),
  assistant_message_completed: z.object({
    ...attributionSchema,
    finalText: z.string(),
    messageId: z.string().optional()
  }),
  tool_call_ready: z.object({
    ...attributionSchema,
    toolName: z.string(),
    toolCallId: z.string(),
    toolInput: z.unknown().optional()
  }),
  tool_started: z.object({
    ...attributionSchema,
    toolCallId: z.string(),
    toolName: z.string().optional()
  }),
  tool_result: z.object({
    ...attributionSchema,
    toolCallId: z.string(),
    toolName: z.string().optional(),
    content: z.string(),
    metadata: z.record(z.string(), z.unknown()).optional(),
    externalized: z.boolean(),
    error: z.boolean().optional()
  }),
  transition: z.object({
    ...attributionSchema,
    reason: z.string(),
    attempt: z.number().optional(),
    retriesRemaining: z.number().optional(),
    errorCode: z.string().optional(),
    message: z.string().optional(),
    retrying: z.boolean().optional()
  }),
  completed: z.object({ ...attributionSchema, finalText: z.string() }),
  error: z.object({ message: z.string(), errorCode: z.string().optional(), traceId: z.string().optional() })
}

/** 内部使用：标记 proposal 接受（同 useAgentTurn.rejected 列表）。 */
export function isRejected(p: Proposal & { rejected?: boolean }): boolean {
  return Boolean(p.rejected)
}

/** 工具：导出 job id 生成（测试用）。 */
export function generateJobId(): string { return newId() }
