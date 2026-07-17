<script setup lang="ts">
import AppCard from '@/components/ui/AppCard.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import type { Proposal } from '@/types/agent'

/**
 * ProposalCard — 提案卡片（web.md §7.3 / §二十一 HITL）
 *
 * - AppCard + 摘要 + 数据支撑 + 双按钮（确认执行 primary / 拒绝 ghost）
 * - 选中边框 border-accent
 */
const props = defineProps<{
  proposal: Proposal
  /** 防止双击 */
  busy?: boolean
}>()

const emit = defineEmits<{
  confirm: []
  reject: []
}>()

function onConfirm(): void {
  emit('confirm')
}
</script>

<template>
  <AppCard
    bordered
    class="proposal-card border-accent bg-accent-soft my-3"
  >
    <header class="flex items-center justify-between mb-2">
      <AppBadge
        tone="accent"
        style="solid"
      >
        {{ proposal.type }}
      </AppBadge>
      <span class="text-xs font-mono text-fg-muted">{{ proposal.proposalId.slice(0, 8) }}</span>
    </header>
    <div class="text-base text-fg-primary mb-2">
      {{ proposal.summary ?? '(无摘要)' }}
    </div>
    <div class="flex gap-2 justify-end">
      <AppButton
        variant="primary"
        :disabled="busy"
        @click="onConfirm"
      >
        确认执行
      </AppButton>
      <AppButton
        variant="ghost"
        :disabled="busy"
        @click="emit('reject')"
      >
        拒绝
      </AppButton>
    </div>
  </AppCard>
</template>
