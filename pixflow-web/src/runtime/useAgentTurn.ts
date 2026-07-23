import { computed, ref } from 'vue'
import { z } from 'zod'
import { confirm as confirmProposal, reject as rejectProposal } from '@/api/confirm'
import { getHistory, stopTurn, type HistoryMessage, type MessageReference } from '@/api/messages'
import { createSseClient } from '@/transport/sse'
import { ApiError } from '@/types/api'
import type { AgentEventName, AgentTurnPhase, AgentTurnSummary, Proposal, TimelineItem, ToolStatus } from '@/types/agent'

interface QueuedTurn {
  id: string
  prompt: string
  references: MessageReference[]
  editing: boolean
  resolve: () => void
  reject: (error: ApiError) => void
}

export interface ConfirmResult { taskId: string }
export interface QueuedMessageView {
  id: string
  prompt: string
  references: MessageReference[]
  editing: boolean
}

const payloadSchemas = {
  assistant_delta: z.strictObject({ text: z.string() }),
  assistant_message_completed: z.strictObject({ messageId: z.string().min(1), finalText: z.string() }),
  tool_status: z.strictObject({ label: z.string().min(1), state: z.enum(['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED']) }),
  transition: z.strictObject({ label: z.string().min(1), state: z.string().optional() }),
  proposal_ready: z.strictObject({
    proposalId: z.string().min(1),
    conversationId: z.string().min(1),
    proposalType: z.enum(['IMAGE_PROCESS', 'IMAGEGEN']),
    title: z.string(),
    summary: z.string(),
    referenceSummaries: z.array(z.string()),
    createdAt: z.string().min(1)
  }),
  completed: z.strictObject({ messageId: z.string().min(1).nullable().optional(), stopped: z.boolean() }),
  error: z.strictObject({ code: z.string().optional(), message: z.string(), traceId: z.string().optional() })
} as const

export function createAgentTurn(opts: { conversationId: string }) {
  const summary = ref<AgentTurnSummary>({ conversationId: opts.conversationId, phase: 'idle', queuedCount: 0 })
  const timeline = ref<TimelineItem[]>([])
  const proposals = ref<Proposal[]>([])
  const queue = ref<QueuedTurn[]>([])
  const userMessages = ref<Array<{ id: string; text: string; references: MessageReference[] }>>([])
  const draft = ref('')
  const draftReferences = ref<MessageReference[]>([])
  const phase = computed(() => summary.value.phase)
  const queuedCount = computed(() => queue.value.length)
  const queuedMessages = computed<QueuedMessageView[]>(() => queue.value.map(({ id, prompt, references, editing }) => ({
    id,
    prompt,
    references: [...references],
    editing
  })))
  const queuePaused = ref(false)
  let active: QueuedTurn | null = null
  let stream: { close: () => void } | null = null
  let sequence = 0
  let userSequence = 0
  let queueSequence = 0
  let terminal = false
  let reconcilingInterruption = false
  let activeAssistantId: string | null = null

  function setPhase(next: AgentTurnPhase, error?: ApiError): void {
    summary.value = { conversationId: opts.conversationId, phase: next, queuedCount: queue.value.length, ...(error ? { error } : {}) }
  }

  function send(prompt: string, references: MessageReference[] = []): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const turn = { id: `queued-${++queueSequence}`, prompt, references: [...references], editing: false, resolve, reject }
      if (active || queuePaused.value || queue.value.length > 0) {
        if (queue.value.length >= 5) {
          reject(new ApiError({ status: 409, errorCode: 'TURN_QUEUE_FULL', message: '消息队列已满', traceId: '' }))
          return
        }
        queue.value = [...queue.value, turn]
        summary.value = { ...summary.value, queuedCount: queue.value.length }
        return
      }
      userMessages.value = [...userMessages.value, {
        id: `user-${++userSequence}`,
        text: prompt,
        references: [...references]
      }]
      start(turn)
    })
  }

  function start(turn: QueuedTurn): void {
    active = turn
    terminal = false
    activeAssistantId = null
    userMessages.value = [...userMessages.value, {
      id: `user-${++userSequence}`,
      text: turn.prompt,
      references: [...turn.references]
    }]
    setPhase('sending')
    stream = createSseClient({
      url: `/api/conversations/${encodeURIComponent(opts.conversationId)}/messages`,
      method: 'POST',
      body: { prompt: turn.prompt, references: turn.references },
      onOpen: () => setPhase('streaming'),
      onEvent: ({ name, data, traceId }) => handleEvent(name, data, traceId),
      onError: interruptActive,
      onClose: () => {
        if (!terminal) interruptActive(new ApiError({ status: 0, errorCode: 'STREAM_INTERRUPTED', message: '消息流意外中断', traceId: '' }))
      }
    })
  }

  function handleEvent(name: string, data: unknown, traceId?: string): void {
    if (!(name in payloadSchemas)) return
    const eventName = name as AgentEventName
    const parsed = payloadSchemas[eventName].safeParse(data)
    if (!parsed.success) {
      failActive(new ApiError({ status: 0, errorCode: 'SSE_PAYLOAD_INVALID', message: 'Agent 事件格式无效', traceId: traceId ?? '' }))
      return
    }
    switch (eventName) {
      case 'assistant_delta': appendDelta((parsed.data as { text: string }).text); break
      case 'assistant_message_completed': completeAssistant(parsed.data as { messageId: string; finalText: string }); break
      case 'tool_status': upsertTool(parsed.data as { label: string; state: ToolStatus }); break
      case 'transition': appendTransition(parsed.data as { label: string; state?: string }); break
      case 'proposal_ready': upsertProposal(parsed.data as Omit<Proposal, 'enabled' | 'rejected' | 'confirmedTaskId'>); break
      case 'completed': completeActive((parsed.data as { stopped: boolean }).stopped); break
      case 'error': {
        const error = parsed.data as { code?: string; message: string; traceId?: string }
        failActive(new ApiError({ status: 0, errorCode: error.code ?? 'AGENT_TURN_FAILED', message: error.message, traceId: error.traceId ?? traceId ?? '' }))
        break
      }
    }
  }

  function appendDelta(text: string): void {
    const current = activeAssistantId ? timeline.value.find((item) => item.id === activeAssistantId) : undefined
    if (current?.type === 'assistant') {
      timeline.value = timeline.value.map((item) => item.id === current.id ? { ...current, text: current.text + text } : item)
      return
    }
    activeAssistantId = `assistant-${++sequence}`
    timeline.value = [...timeline.value, { id: activeAssistantId, type: 'assistant', text, status: 'streaming' }]
  }

  function completeAssistant(payload: { messageId: string; finalText: string }): void {
    const currentId = activeAssistantId
    if (currentId) {
      timeline.value = timeline.value.map((item) => item.id === currentId && item.type === 'assistant'
        ? { ...item, text: payload.finalText, status: 'completed', messageId: payload.messageId }
        : item)
    } else {
      timeline.value = [...timeline.value, {
        id: `assistant-${++sequence}`,
        type: 'assistant',
        text: payload.finalText,
        status: 'completed',
        messageId: payload.messageId
      }]
    }
    activeAssistantId = null
  }

  function upsertTool(payload: { label: string; state: ToolStatus }): void {
    const index = timeline.value.findIndex((item) => item.type === 'tool' && item.label === payload.label)
    const item = { id: index >= 0 ? timeline.value[index]!.id : `tool-${++sequence}`, type: 'tool' as const, label: payload.label, status: payload.state }
    timeline.value = index >= 0
      ? timeline.value.map((current, currentIndex) => currentIndex === index ? item : current)
      : [...timeline.value, item]
  }

  function appendTransition(payload: { label: string; state?: string }): void {
    timeline.value = [...timeline.value, { id: `transition-${++sequence}`, type: 'transition', ...payload }]
  }

  function upsertProposal(payload: Omit<Proposal, 'enabled' | 'rejected' | 'confirmedTaskId'>): void {
    const proposal: Proposal = { ...payload, enabled: false }
    const index = proposals.value.findIndex((item) => item.proposalId === proposal.proposalId)
    proposals.value = index >= 0
      ? proposals.value.map((item, currentIndex) => currentIndex === index ? { ...item, ...proposal } : item)
      : [...proposals.value, proposal]
  }

  function completeActive(stopped: boolean): void {
    terminal = true
    stream = null
    proposals.value = proposals.value.map((proposal) => proposal.rejected || proposal.confirmedTaskId ? proposal : { ...proposal, enabled: true })
    setPhase(stopped ? 'cancelled' : 'completed')
    const completed = active
    active = null
    completed?.resolve()
    drainQueue()
  }

  function failActive(error: ApiError): void {
    if (terminal) return
    terminal = true
    const currentId = activeAssistantId
    if (currentId) {
      timeline.value = timeline.value.map((item) => item.id === currentId && item.type === 'assistant'
        ? { ...item, status: 'interrupted' }
        : item)
    }
    timeline.value = [...timeline.value, { id: `error-${++sequence}`, type: 'error', message: error.message, errorCode: error.errorCode, traceId: error.traceId }]
    const failed = active
    active = null
    stream = null
    queuePaused.value = queue.value.length > 0
    setPhase('error', error)
    failed?.reject(error)
  }

  function interruptActive(error: ApiError): void {
    if (terminal || reconcilingInterruption) return
    reconcilingInterruption = true
    const interruptedTurn = active
    void reconcileHistory(interruptedTurn).finally(() => {
      reconcilingInterruption = false
      if (active === interruptedTurn) failActive(error)
    })
  }

  async function reconcileHistory(interruptedTurn: QueuedTurn | null): Promise<void> {
    if (!interruptedTurn) return
    try {
      const history = await getHistory(opts.conversationId, { page: 1, size: 50 })
      if (active !== interruptedTurn) return
      const latestAssistant = history.records.filter((message) => message.role === 'ASSISTANT').at(-1)
      if (latestAssistant) reconcileAssistant(latestAssistant)
    } catch {
      // 历史对账失败不覆盖原始 transport 错误，当前 partial 仍会标记为 interrupted。
    }
  }

  function reconcileAssistant(message: HistoryMessage): void {
    const existing = timeline.value.find((item) => item.type === 'assistant' && item.messageId === message.messageId)
    if (existing?.type === 'assistant') {
      timeline.value = timeline.value.map((item) => item.id === existing.id
        ? { ...existing, text: message.content, status: 'completed' }
        : item)
      return
    }
    completeAssistant({ messageId: message.messageId, finalText: message.content })
  }

  function drainQueue(): void {
    if (active || queuePaused.value || hasPendingProposals()) return
    const [next, ...rest] = queue.value
    if (next?.editing) return
    queue.value = rest
    summary.value = { ...summary.value, queuedCount: rest.length }
    if (next) start(next)
  }

  function hasPendingProposals(): boolean {
    return proposals.value.some((proposal) => proposal.enabled && !proposal.rejected && !proposal.confirmedTaskId)
  }

  function beginQueuedEdit(id: string): void {
    queue.value = queue.value.map((item) => item.id === id ? { ...item, editing: true } : item)
  }

  function saveQueuedEdit(id: string, prompt: string, references: MessageReference[]): void {
    if (!prompt.trim() && references.length === 0) return
    queue.value = queue.value.map((item) => item.id === id
      ? { ...item, prompt: prompt.trim(), references: [...references], editing: false }
      : item)
    drainQueue()
  }

  function cancelQueuedEdit(id: string): void {
    queue.value = queue.value.map((item) => item.id === id ? { ...item, editing: false } : item)
    drainQueue()
  }

  function cancelQueued(id: string): void {
    const cancelled = queue.value.find((item) => item.id === id)
    if (!cancelled) return
    queue.value = queue.value.filter((item) => item.id !== id)
    summary.value = { ...summary.value, queuedCount: queue.value.length }
    cancelled.reject(new ApiError({
      status: 0,
      errorCode: 'TURN_QUEUE_CANCELLED',
      message: '已取消排队消息',
      traceId: ''
    }))
    if (queue.value.length === 0) queuePaused.value = false
    drainQueue()
  }

  function continueQueue(): void {
    queuePaused.value = false
    drainQueue()
  }

  async function confirm(proposalId: string): Promise<ConfirmResult> {
    const proposal = proposals.value.find((item) => item.proposalId === proposalId)
    if (!proposal?.enabled) throw new ApiError({ status: 409, errorCode: 'PROPOSAL_NOT_READY', message: '方案尚不可操作', traceId: '' })
    const response = await confirmProposal(opts.conversationId, proposalId)
    proposals.value = proposals.value.map((item) => item.proposalId === proposalId ? { ...item, confirmedTaskId: response.taskId } : item)
    drainQueue()
    return { taskId: response.taskId }
  }

  async function reject(proposalId: string): Promise<void> {
    const proposal = proposals.value.find((item) => item.proposalId === proposalId)
    if (!proposal || proposal.rejected) return
    await rejectProposal(opts.conversationId, proposalId)
    proposals.value = proposals.value.map((item) => item.proposalId === proposalId ? { ...item, rejected: true } : item)
    drainQueue()
  }

  function abort(): void {
    if (!active) return
    void stopTurn(opts.conversationId).catch((error: unknown) => {
      failActive(ApiError.fromUnknown(error, {
        status: 0,
        errorCode: 'STOP_TURN_FAILED',
        message: '停止失败',
        traceId: ''
      }))
    })
  }

  function dispose(): void {
    if (!active && queue.value.length === 0) return
    const currentStream = stream
    const error = new ApiError({
      status: 401,
      errorCode: 'AUTH_SESSION_ENDED',
      message: '登录状态已结束',
      traceId: ''
    })
    if (active) failActive(error)
    const abandoned = queue.value
    queue.value = []
    queuePaused.value = false
    summary.value = { ...summary.value, queuedCount: 0 }
    for (const queued of abandoned) queued.reject(error)
    currentStream?.close()
  }

  function setProposal(proposal: Proposal): void {
    const normalized = { ...proposal, enabled: proposal.enabled ?? true }
    const index = proposals.value.findIndex((item) => item.proposalId === proposal.proposalId)
    proposals.value = index >= 0
      ? proposals.value.map((item, currentIndex) => currentIndex === index ? normalized : item)
      : [...proposals.value, normalized]
  }

  return {
    state: summary,
    phase,
    timeline,
    proposals,
    userMessages,
    draft,
    draftReferences,
    queuedCount,
    queuedMessages,
    queuePaused,
    send,
    confirm,
    reject,
    abort,
    beginQueuedEdit,
    saveQueuedEdit,
    cancelQueuedEdit,
    cancelQueued,
    continueQueue,
    dispose,
    setProposal,
    __setPendingProposal: setProposal
  }
}

export type AgentTurn = ReturnType<typeof createAgentTurn>

export function isRejected(proposal: Proposal): boolean {
  return Boolean(proposal.rejected)
}
