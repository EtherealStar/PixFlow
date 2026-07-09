<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import MessageStream from '@/components/chat/MessageStream.vue'
import ProposalCard from '@/components/chat/ProposalCard.vue'
import ChallengeDialog from '@/components/chat/ChallengeDialog.vue'
import Composer from '@/components/chat/Composer.vue'
import TaskProgressCard from '@/components/tasks/TaskProgressCard.vue'
import { useChatSession } from '@/runtime/useChatSession'

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
const chat = useChatSession({ route, router })
</script>

<template>
  <section class="chat-page flex flex-col h-full bg-bg-page">
    <header class="chat-head px-6 py-3 border-b border-border bg-bg-panel">
      <h2 class="text-sm font-medium text-fg-primary">
        {{ chat.activeConversationId.value ? `会话 ${chat.activeConversationId.value.slice(0, 8)}` : '新建对话' }}
      </h2>
    </header>

    <div class="chat-body flex-1 overflow-y-auto">
      <MessageStream :deltas="chat.streamDeltas.value" :user-messages="chat.userMessages.value" />

      <ProposalCard
        v-for="p in chat.visibleProposals.value"
        :key="p.proposalId"
        :proposal="p"
        :challenge-prompt="chat.currentChallenge.value?.prompt"
        :awaiting-challenge="chat.currentPhase.value === 'awaiting_challenge'"
        :busy="chat.sending.value || chat.currentPhase.value === 'awaiting_confirm'"
        @confirm="(ans?: string) => chat.confirmProposal(p, ans)"
        @reject="chat.rejectProposal(p)"
      />

      <TaskProgressCard
        v-for="t in chat.taskRefs.value"
        :key="t.taskId"
        :task-id="t.taskId"
        :conversation-id="t.conversationId"
        class="mx-6 my-2"
      />

      <ChallengeDialog
        v-model:visible="chat.challengeVisible.value"
        :proposal="chat.activeProposal.value"
        :challenge="chat.currentChallenge.value"
        :error="chat.turnError.value"
        @submit="(ans: string) => {
          const p = chat.activeProposal.value
          if (p) chat.confirmProposal(p, ans)
        }"
      />
    </div>

    <footer class="chat-foot border-t border-border bg-bg-panel px-4 py-3">
      <Composer
        v-model="chat.composerText.value"
        :sending="chat.sending.value || chat.uploadingAttachment.value || chat.currentPhase.value === 'sending'"
        :streaming="chat.currentPhase.value === 'streaming' || chat.currentPhase.value === 'sending'"
        :has-attachments="chat.pendingAttachments.value.length > 0"
        @attach-files="chat.attachFiles"
        @send="chat.sendComposer"
        @stop="chat.stop"
      />
    </footer>
  </section>
</template>
