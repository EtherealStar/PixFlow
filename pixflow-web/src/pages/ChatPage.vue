<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MessageStream from '@/components/chat/MessageStream.vue'
import ProposalCard from '@/components/chat/ProposalCard.vue'
import ChallengeDialog from '@/components/chat/ChallengeDialog.vue'
import Composer from '@/components/chat/Composer.vue'
import TaskProgressCard from '@/components/tasks/TaskProgressCard.vue'
import { useConversationsStore } from '@/stores/conversations'
import { useAgentTurnsStore } from '@/stores/agentTurns'
import { useToastStore } from '@/stores/toast'
import { createAgentTurn, isRejected, type AgentTurn } from '@/runtime/useAgentTurn'
import type { ApiError } from '@/types/api'
import type { ConfirmationChallenge, Proposal } from '@/types/agent'

/**
 * 会话页（/chat/:cid），web.md §十一 / §二十一
 *
 * - Composer 接管输入（@ 提及 / Ctrl+Enter / 附件）
 * - MessageStream 渲染 agent deltas + 用户消息
 * - ProposalCard 列表：HITL 二次确认入口
 * - ChallengeDialog：答案错误时弹出
 * - TaskProgressCard：当消息流中带 taskId 时挂载
 */
const route = useRoute()
const router = useRouter()
const conversations = useConversationsStore()
const agentTurns = useAgentTurnsStore()
const toast = useToastStore()

const sending = ref(false)
const challengeVisible = ref(false)
const currentChallenge = ref<ConfirmationChallenge | null>(null)
const userMessages = ref<Array<{ id: string; text: string }>>([])
const taskRefs = ref<Array<{ taskId: string; conversationId: string }>>([])

const cid = computed(() => (route.params.cid as string) || 'new')

const turn: AgentTurn = createAgentTurn({ conversationId: cid.value })

watch(() => turn.state.value, (s) => {
  agentTurns.set(cid.value, s)
}, { deep: true })

onMounted(async () => {
  if (cid.value === 'new') {
    const c = await conversations.create()
    if (route.query.q) {
      router.replace({ path: `/chat/${c.conversationId}`, query: { q: route.query.q } })
    } else {
      router.replace(`/chat/${c.conversationId}`)
    }
    return
  }
  await conversations.select(cid.value)

  if (route.query.q) {
    const q = route.query.q as string
    router.replace({ path: `/chat/${cid.value}` })
    onSend(q)
  }
})

async function onSend(text: string): Promise<void> {
  if (!text.trim()) return
  sending.value = true
  userMessages.value.push({ id: `u-${Date.now()}`, text })
  try {
    await turn.send(text)
    // 若 turn state 出现 taskId，挂载进度卡
    const lastTaskId = turn.state.value.lastTaskId
    if (lastTaskId && !taskRefs.value.find((t) => t.taskId === lastTaskId)) {
      taskRefs.value.push({ taskId: lastTaskId, conversationId: cid.value })
    }
  } catch (e) {
    const err = e as ApiError
    toast.push({ variant: 'danger', message: err.message })
  } finally {
    sending.value = false
  }
}

async function onConfirmProposal(proposal: Proposal, answer?: string): Promise<void> {
  try {
    const r = await turn.confirm(proposal.proposalId, answer)
    if (r.taskId) {
      toast.push({ variant: 'success', message: `已创建任务：${r.taskId.slice(0, 8)}` })
      taskRefs.value.push({ taskId: r.taskId, conversationId: cid.value })
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

function onRejectProposal(proposal: Proposal): void {
  turn.reject(proposal.proposalId)
  userMessages.value.push({ id: `r-${Date.now()}`, text: `[已拒绝方案 ${proposal.proposalId.slice(0, 8)}]` })
}

function onStop(): void {
  turn.abort()
  sending.value = false
}
</script>

<template>
  <section class="chat-page flex flex-col h-full bg-bg-page">
    <header class="chat-head px-6 py-3 border-b border-border bg-bg-panel">
      <h2 class="text-sm font-medium text-fg-primary">
        {{ cid === 'new' ? '新建对话' : `会话 ${cid.slice(0, 8)}` }}
      </h2>
    </header>

    <div class="chat-body flex-1 overflow-y-auto">
      <MessageStream :deltas="turn.deltas.value" :user-messages="userMessages" />

      <ProposalCard
        v-for="p in turn.proposals.value.filter((pp) => !isRejected(pp as never))"
        :key="p.proposalId"
        :proposal="p"
        :challenge-prompt="currentChallenge?.prompt"
        :awaiting-challenge="turn.phase.value === 'awaiting_challenge'"
        :busy="sending"
        @confirm="(ans?: string) => onConfirmProposal(p, ans)"
        @reject="onRejectProposal(p)"
      />

      <TaskProgressCard
        v-for="t in taskRefs"
        :key="t.taskId"
        :task-id="t.taskId"
        :conversation-id="t.conversationId"
        class="mx-6 my-2"
      />

      <ChallengeDialog
        v-model:visible="challengeVisible"
        :proposal="turn.proposals.value[0] ?? null"
        :challenge="currentChallenge"
        :error="turn.state.value.error ?? null"
        @submit="(ans: string) => {
          const p = turn.proposals.value[0]
          if (p) onConfirmProposal(p, ans)
        }"
      />
    </div>

    <footer class="chat-foot border-t border-border bg-bg-panel px-4 py-3">
      <Composer
        :busy="sending"
        :streaming="turn.phase.value === 'streaming' || turn.phase.value === 'sending'"
        @send="onSend"
        @stop="onStop"
      />
    </footer>
  </section>
</template>