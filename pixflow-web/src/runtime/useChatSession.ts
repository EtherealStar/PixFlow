import { computed, ref, shallowRef, watch } from 'vue'
import type { RouteLocationNormalizedLoaded, Router } from 'vue-router'
import { isRejected, type AgentTurn } from '@/runtime/useAgentTurn'
import { useAgentTurnsStore } from '@/stores/agentTurns'
import { useConversationsStore } from '@/stores/conversations'
import { useToastStore } from '@/stores/toast'
import { ApiError } from '@/types/api'
import type { Proposal, TimelineItem } from '@/types/agent'
import type { MessageReference } from '@/api/messages'

export interface ChatRouteLike {
  params: RouteLocationNormalizedLoaded['params']
  query: RouteLocationNormalizedLoaded['query']
}

export interface ChatSessionOptions {
  route: ChatRouteLike
  router: Router
}

export function useChatSession(opts: ChatSessionOptions) {
  const conversations = useConversationsStore()
  const agentTurns = useAgentTurnsStore()
  const toast = useToastStore()

  const sending = ref(false)
  const activeConversationId = ref<string | null>(null)
  const turnRef = shallowRef<AgentTurn | null>(null)
  const bootstrapping = ref(false)
  let lastAutoSendKey = ''

  const cid = computed(() => (opts.route.params.conversationId as string) || 'new')
  const streamTimeline = computed<TimelineItem[]>(() => turnRef.value?.timeline.value ?? [])
  const currentPhase = computed(() => turnRef.value?.phase.value ?? 'idle')
  const queuedCount = computed(() => turnRef.value?.queuedCount.value ?? 0)
  const queuedMessages = computed(() => turnRef.value?.queuedMessages.value ?? [])
  const queuePaused = computed(() => turnRef.value?.queuePaused.value ?? false)
  const turnError = computed(() => turnRef.value?.state.value.error ?? null)
  const composerText = computed({
    get: () => turnRef.value?.draft.value ?? '',
    set: (value: string) => { if (turnRef.value) turnRef.value.draft.value = value }
  })
  const composerReferences = computed({
    get: () => turnRef.value?.draftReferences.value ?? [],
    set: (value: MessageReference[]) => { if (turnRef.value) turnRef.value.draftReferences.value = value }
  })
  const userMessages = computed(() => turnRef.value?.userMessages.value ?? [])
  const visibleProposals = computed(() => {
    const list = turnRef.value?.proposals.value ?? []
    return list.filter((p) => !isRejected(p))
  })
  const activeProposal = computed(() => visibleProposals.value[0] ?? null)

  watch(() => turnRef.value?.state.value, (s) => {
    const conversationId = activeConversationId.value
    if (s && conversationId) {
      agentTurns.set(conversationId, s)
    }
  }, { deep: true })

  watch(
    () => [cid.value, opts.route.query.q] as const,
    () => { void ensureConversationAndMaybeSend() },
    { immediate: true }
  )

  async function ensureConversationAndMaybeSend(): Promise<void> {
    if (bootstrapping.value) return
    bootstrapping.value = true
    try {
      let conversationId = cid.value
      if (conversationId === 'new') {
        const c = await conversations.create()
        conversationId = c.conversationId
        await opts.router.replace({ path: `/chat/${conversationId}`, query: opts.route.query.q ? { q: opts.route.query.q } : {} })
      } else {
        await conversations.select(conversationId)
      }
      if (activeConversationId.value !== conversationId || !turnRef.value) {
        activeConversationId.value = conversationId
        turnRef.value = agentTurns.getOrCreateRuntime(conversationId)
      }
      const q = typeof opts.route.query.q === 'string' ? opts.route.query.q.trim() : ''
      if (q) {
        const key = `${conversationId}:${q}`
        if (lastAutoSendKey !== key) {
          lastAutoSendKey = key
          await opts.router.replace({ path: `/chat/${conversationId}` })
          sendText(q)
        }
      }
    } finally {
      bootstrapping.value = false
    }
  }

  function sendText(text: string, references: MessageReference[] = []): void {
    if ((!text.trim() && references.length === 0) || !turnRef.value || !activeConversationId.value) return
    const sentText = text.trim()
    sending.value = true
    try {
      void turnRef.value.send(sentText, references).catch((e: unknown) => {
        const err = ApiError.fromUnknown(e, { status: 0, errorCode: 'STREAM_ERROR', message: '发送失败', traceId: '' })
        if (err.errorCode !== 'TURN_QUEUE_CANCELLED') {
          toast.push({ variant: 'danger', message: err.message })
        }
      })
    } finally {
      sending.value = false
    }
  }

  function sendComposer(): void {
    const text = composerText.value
    if (!text.trim() && composerReferences.value.length === 0) return
    composerText.value = ''
    const references = composerReferences.value
    composerReferences.value = []
    void sendText(text, references)
  }

  async function confirmProposal(proposal: Proposal): Promise<void> {
    if (!turnRef.value || !activeConversationId.value) return
    try {
      const r = await turnRef.value.confirm(proposal.proposalId)
      if (r.taskId) {
        toast.push({ variant: 'success', message: `已创建任务：${r.taskId.slice(0, 8)}` })
      }
    } catch (e: unknown) {
      const err = ApiError.fromUnknown(e, { status: 0, errorCode: 'NETWORK_ERROR', message: '确认失败', traceId: '' })
      toast.push({ variant: 'danger', message: err.message })
    }
  }

  async function rejectProposal(proposal: Proposal): Promise<void> {
    if (!turnRef.value) return
    try {
      await turnRef.value.reject(proposal.proposalId)
    } catch (e: unknown) {
      const err = ApiError.fromUnknown(e, { status: 0, errorCode: 'NETWORK_ERROR', message: '拒绝失败', traceId: '' })
      toast.push({ variant: 'danger', message: err.message })
    }
  }

  function stop(): void {
    turnRef.value?.abort()
    sending.value = false
  }

  return {
    activeConversationId,
    composerText,
    composerReferences,
    sending,
    streamTimeline,
    currentPhase,
    queuedCount,
    queuedMessages,
    queuePaused,
    turnError,
    visibleProposals,
    activeProposal,
    userMessages,
    ensureConversationAndMaybeSend,
    sendText,
    sendComposer,
    confirmProposal,
    rejectProposal,
    stop,
    beginQueuedEdit: (id: string) => turnRef.value?.beginQueuedEdit(id),
    saveQueuedEdit: (id: string, prompt: string, references: MessageReference[]) => turnRef.value?.saveQueuedEdit(id, prompt, references),
    cancelQueuedEdit: (id: string) => turnRef.value?.cancelQueuedEdit(id),
    cancelQueued: (id: string) => turnRef.value?.cancelQueued(id),
    continueQueue: () => turnRef.value?.continueQueue()
  }
}
