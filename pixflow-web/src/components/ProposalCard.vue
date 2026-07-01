<script setup lang="ts">
import { ref } from 'vue'
import type { Proposal } from '@/types/agent'

const props = defineProps<{
  proposal: Proposal
  challengePrompt?: string
  awaitingChallenge: boolean
  /** 防止双击 */
  busy?: boolean
}>()

const emit = defineEmits<{
  confirm: [answer?: string]
  reject: []
}>()

const answer = ref('')

function onConfirm(): void {
  if (props.awaitingChallenge) {
    emit('confirm', answer.value || undefined)
  } else {
    emit('confirm')
  }
}
</script>

<template>
  <div class="proposal-card">
    <header class="head">
      <span class="type">{{ proposal.type }}</span>
      <span class="id">{{ proposal.proposalId.slice(0, 8) }}</span>
    </header>
    <div class="summary">{{ proposal.summary ?? '(无摘要)' }}</div>
    <div v-if="awaitingChallenge && challengePrompt" class="challenge">
      <div class="challenge-prompt">{{ challengePrompt }}</div>
      <el-input v-model="answer" placeholder="请输入答案..." />
    </div>
    <div class="actions">
      <el-button type="primary" :disabled="busy" @click="onConfirm">确认执行</el-button>
      <el-button :disabled="busy" @click="emit('reject')">拒绝</el-button>
    </div>
  </div>
</template>

<style scoped>
.proposal-card {
  border: 1px solid var(--color-primary);
  border-radius: 8px;
  padding: 12px;
  margin: 12px 0;
  background: #f0f9ff;
}
.head { display: flex; justify-content: space-between; margin-bottom: 8px; }
.type {
  background: var(--color-primary);
  color: white;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 11px;
}
.id { font-family: monospace; font-size: 11px; color: var(--color-text-mute); }
.summary { margin-bottom: 8px; font-size: 14px; }
.challenge { margin: 8px 0; padding: 8px; background: var(--color-bg); border-radius: 4px; }
.challenge-prompt { margin-bottom: 6px; font-size: 13px; }
.actions { display: flex; gap: 8px; justify-content: flex-end; }
</style>