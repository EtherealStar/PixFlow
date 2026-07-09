import { computed, markRaw, ref, shallowRef, watch, type Ref } from 'vue'
import type { RouteLocationNormalizedLoaded, Router } from 'vue-router'
import { uploadAttachment, type UploadAttachmentResponse } from '@/api/attachments'
import { createAgentTurn, isRejected, type AgentTurn } from '@/runtime/useAgentTurn'
import { useAgentTurnsStore } from '@/stores/agentTurns'
import { useConversationsStore } from '@/stores/conversations'
import { useToastStore } from '@/stores/toast'
import type { ApiError } from '@/types/api'
import type { ConfirmationChallenge, Proposal, TimelineItem } from '@/types/agent'

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
  const uploadingAttachment = ref(false)
  const composerText = ref('')
  const challengeVisible = ref(false)
  const currentChallenge = ref<ConfirmationChallenge | null>(null)
  const userMessages = ref<Array<{ id: string; text: string }>>([])
  const pendingAttachments = ref<UploadAttachmentResponse[]>([])
  const taskRefs = ref<Array<{ taskId: string; conversationId: string }>>([])
  const activeConversationId = ref<string | null>(null)
  const turnRef = shallowRef<AgentTurn | null>(null)
  const bootstrapping = ref(false)
  let lastAutoSendKey = ''

  const cid = computed(() => (opts.route.params.cid as string) || 'new')
  const streamTimeline = computed<TimelineItem[]>(() => turnRef.value?.timeline.value ?? [])
  const currentPhase = computed(() => turnRef.value?.phase.value ?? 'idle')
  const queuedCount = computed(() => turnRef.value?.queuedCount.value ?? 0)
  const turnError = computed(() => turnRef.value?.state.value.error ?? null)
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
        turnRef.value?.abort()
        activeConversationId.value = conversationId
        turnRef.value = markRaw(createAgentTurn({ conversationId }))
      }
      const q = typeof opts.route.query.q === 'string' ? opts.route.query.q.trim() : ''
      if (q) {
        const key = `${conversationId}:${q}`
        if (lastAutoSendKey !== key) {
          lastAutoSendKey = key
          await opts.router.replace({ path: `/chat/${conversationId}` })
          await sendText(q)
        }
      }
    } finally {
      bootstrapping.value = false
    }
  }

  async function sendText(text: string): Promise<void> {
    if ((!text.trim() && pendingAttachments.value.length === 0) || !turnRef.value || !activeConversationId.value) return
    const sentText = text.trim()
    sending.value = true
    const packageId = pendingAttachments.value[0]?.packageId
    const attachmentText = pendingAttachments.value.length > 0 ? `（已附加 ${pendingAttachments.value.length} 个素材包）` : ''
    pendingAttachments.value = []
    userMessages.value.push({ id: `u-${Date.now()}`, text: sentText || attachmentText })
    try {
      await turnRef.value.send(sentText, {
        packageId
      })
      attachTaskIfPresent(turnRef, taskRefs, activeConversationId.value)
    } catch (e) {
      const err = e as ApiError
      toast.push({ variant: 'danger', message: err.message })
    } finally {
      sending.value = false
    }
  }

  function sendComposer(): void {
    const text = composerText.value
    if (!text.trim() && pendingAttachments.value.length === 0) return
    composerText.value = ''
    void sendText(text)
  }

  async function confirmProposal(proposal: Proposal, answer?: string): Promise<void> {
    if (!turnRef.value || !activeConversationId.value) return
    try {
      const r = await turnRef.value.confirm(proposal.proposalId, answer)
      if (r.challenge) {
        currentChallenge.value = r.challenge
        challengeVisible.value = true
        return
      }
      if (r.taskId) {
        toast.push({ variant: 'success', message: `已创建任务：${r.taskId.slice(0, 8)}` })
        appendTaskRef(taskRefs, r.taskId, activeConversationId.value)
        challengeVisible.value = false
      }
    } catch (e) {
      const err = e as ApiError
      if (err.errorCode === 'PROPOSAL_CHALLENGE_FAILED') {
        challengeVisible.value = true
      } else {
        toast.push({ variant: 'danger', message: err.message })
      }
    }
  }

  function rejectProposal(proposal: Proposal): void {
    turnRef.value?.reject(proposal.proposalId)
    userMessages.value.push({ id: `r-${Date.now()}`, text: `[已拒绝方案 ${proposal.proposalId.slice(0, 8)}]` })
  }

  function stop(): void {
    turnRef.value?.abort()
    sending.value = false
  }

  async function attachFiles(files: File[]): Promise<void> {
    if (!activeConversationId.value || files.length === 0) return
    uploadingAttachment.value = true
    try {
      for (const file of files) {
        const result = await uploadAttachment(activeConversationId.value, file, {
          filename: file.name,
          contentType: file.type
        })
        pendingAttachments.value.push(result)
      }
      toast.push({ variant: 'success', message: `已上传 ${files.length} 个附件，将随下一条消息发送` })
    } catch (e) {
      const err = e as ApiError
      toast.push({ variant: 'danger', message: err.message ?? '附件上传失败' })
    } finally {
      uploadingAttachment.value = false
    }
  }

  return {
    activeConversationId,
    composerText,
    sending,
    uploadingAttachment,
    streamTimeline,
    currentPhase,
    queuedCount,
    turnError,
    visibleProposals,
    activeProposal,
    challengeVisible,
    currentChallenge,
    userMessages,
    pendingAttachments,
    taskRefs,
    ensureConversationAndMaybeSend,
    sendText,
    sendComposer,
    confirmProposal,
    rejectProposal,
    stop,
    attachFiles
  }
}

function attachTaskIfPresent(
  turnRef: Readonly<Ref<AgentTurn | null>>,
  taskRefs: Ref<Array<{ taskId: string; conversationId: string }>>,
  conversationId: string
): void {
  const taskId = turnRef.value?.state.value.taskId
  if (taskId) {
    appendTaskRef(taskRefs, taskId, conversationId)
  }
}

function appendTaskRef(
  taskRefs: Ref<Array<{ taskId: string; conversationId: string }>>,
  taskId: string,
  conversationId: string
): void {
  if (!taskRefs.value.find((t) => t.taskId === taskId)) {
    taskRefs.value.push({ taskId, conversationId })
  }
}
