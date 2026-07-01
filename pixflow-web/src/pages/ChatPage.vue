<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import MessageStream from '@/components/MessageStream.vue'
import ProposalCard from '@/components/ProposalCard.vue'
import ChallengeDialog from '@/components/ChallengeDialog.vue'
import { useConversationsStore } from '@/stores/conversations'
import { useAgentTurnsStore } from '@/stores/agentTurns'
import { createAgentTurn, isRejected, type AgentTurn } from '@/runtime/useAgentTurn'
import type { ApiError } from '@/types/api'
import type { ConfirmationChallenge, Proposal } from '@/types/agent'

const route = useRoute()
const router = useRouter()
const conversations = useConversationsStore()
const agentTurns = useAgentTurnsStore()

const prompt = ref('')
const sending = ref(false)
const challengeVisible = ref(false)
const currentChallenge = ref<ConfirmationChallenge | null>(null)
const userMessages = ref<Array<{ id: string; text: string }>>([])

const cid = computed(() => (route.params.cid as string) || 'new')

const turn: AgentTurn = createAgentTurn({ conversationId: cid.value })

watch(() => turn.state.value, (s) => {
  agentTurns.set(cid.value, s)
}, { deep: true })

onMounted(async () => {
  if (cid.value === 'new') {
    const c = await conversations.create()
    router.replace(`/chat/${c.conversationId}`)
    return
  }
  await conversations.select(cid.value)
})

async function handleSend(): Promise<void> {
  const text = prompt.value.trim()
  if (!text) return
  sending.value = true
  prompt.value = ''
  userMessages.value.push({ id: Date.now().toString(), text })
  try {
    await turn.send(text)
  } catch (e) {
    const err = e as ApiError
    ElMessage.error(err.message)
  } finally {
    sending.value = false
  }
}

async function onConfirmProposal(proposal: Proposal, answer?: string): Promise<void> {
  try {
    const r = await turn.confirm(proposal.proposalId, answer)
    if (r.taskId) {
      ElMessage.success(`已创建任务：${r.taskId.slice(0, 8)}`)
      router.push(`/chat/${cid.value}/tasks/${r.taskId}`)
      challengeVisible.value = false
    }
  } catch (e) {
    const err = e as ApiError
    if (err.errorCode === 'PROPOSAL_CHALLENGE_FAILED') {
      // 焦点已在 ChallengeDialog 内
      challengeVisible.value = true
    } else {
      ElMessage.error(err.message)
    }
  }
}

function onRejectProposal(proposal: Proposal): void {
  turn.reject(proposal.proposalId)
  userMessages.value.push({ id: `reject-${Date.now()}`, text: `[已拒绝方案 ${proposal.proposalId.slice(0, 8)}]` })
}

function onStop(): void {
  turn.abort()
}
</script>

<template>
  <section class="chat-page">
    <header class="chat-head">
      <h2>{{ cid === 'new' ? '新建对话' : `会话 ${cid.slice(0, 8)}` }}</h2>
    </header>

    <div class="chat-body">
      <MessageStream :deltas="turn.deltas.value" :user-messages="userMessages" />

      <ProposalCard
        v-for="p in turn.proposals.value.filter(p => !isRejected(p as any))"
        :key="p.proposalId"
        :proposal="p"
        :challenge-prompt="currentChallenge?.prompt"
        :awaiting-challenge="turn.phase.value === 'awaiting_challenge'"
        :busy="sending"
        @confirm="(ans?: string) => onConfirmProposal(p, ans)"
        @reject="onRejectProposal(p)"
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

    <footer class="chat-foot">
      <el-input
        v-model="prompt"
        type="textarea"
        :rows="3"
        placeholder="输入消息...（Ctrl+Enter 发送）"
        @keydown.ctrl.enter.prevent="handleSend"
      />
      <div class="actions">
        <el-button v-if="turn.phase.value === 'streaming' || turn.phase.value === 'sending'" @click="onStop">停止</el-button>
        <el-button v-else type="primary" :loading="sending" @click="handleSend">发送</el-button>
      </div>
    </footer>
  </section>
</template>

<style scoped>
.chat-page { display: flex; flex-direction: column; height: 100%; }
.chat-head { padding: 12px 16px; border-bottom: 1px solid var(--color-border); background: var(--color-bg); }
.chat-head h2 { margin: 0; font-size: 14px; }
.chat-body { flex: 1; overflow-y: auto; }
.chat-foot {
  padding: 12px 16px;
  border-top: 1px solid var(--color-border);
  background: var(--color-bg);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.actions { display: flex; gap: 8px; justify-content: flex-end; }
</style>