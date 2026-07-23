<script setup lang="ts">
import AppButton from '@/components/ui/AppButton.vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import IconPackage from '@/components/icons/IconPackage.vue'
import type { Proposal } from '@/types/agent'

/**
 * ProposalCard — 提案卡片（frontend/chat.md / ui-visual.md）
 *
 * - 确认一键完成（后端以 proposalId 做幂等，前端不造挑战题）
 * - 卡片可随流式出现，但本轮回答完成前操作保持禁用，并说明原因
 * - 原始 proposalId 不上界面
 */
defineProps<{
  proposal: Proposal
  /** 防止双击 */
  busy?: boolean
}>()

const emit = defineEmits<{
  confirm: []
  reject: []
}>()
</script>

<template>
  <article class="proposal-card">
    <header class="card-head">
      <AppBadge
        tone="accent"
        style="soft"
      >
        {{ proposal.proposalType === 'IMAGEGEN' ? '图片生成' : '图片处理' }}
      </AppBadge>
      <span
        v-if="proposal.confirmedTaskId"
        class="confirmed-hint"
      >已确认，任务进行中</span>
    </header>

    <h3 class="card-title">
      {{ proposal.title }}
    </h3>
    <p class="card-summary">
      {{ proposal.summary }}
    </p>

    <ul
      v-if="proposal.referenceSummaries.length"
      class="card-refs"
    >
      <li
        v-for="(refText, i) in proposal.referenceSummaries"
        :key="i"
        class="card-ref"
      >
        <IconPackage
          :size="12"
          class="shrink-0"
        />
        <span class="truncate">{{ refText }}</span>
      </li>
    </ul>

    <!-- 已确认的提案为终态：头部展示状态，不再渲染操作 -->
    <footer
      v-if="!proposal.confirmedTaskId"
      class="card-foot"
    >
      <span
        v-if="!proposal.enabled"
        class="disabled-hint"
      >本轮回答完成后可确认</span>
      <div class="card-actions">
        <AppButton
          variant="ghost"
          size="sm"
          :disabled="busy || !proposal.enabled"
          @click="emit('reject')"
        >
          拒绝
        </AppButton>
        <AppButton
          variant="primary"
          size="sm"
          :loading="busy"
          :disabled="!proposal.enabled"
          @click="emit('confirm')"
        >
          确认执行
        </AppButton>
      </div>
    </footer>
  </article>
</template>

<style scoped>
.proposal-card {
  width: 100%;
  max-width: 768px;
  margin: 0 auto;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 16px;
  box-shadow: var(--shadow-sm);
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}
.confirmed-hint {
  font-size: 12px;
  color: var(--success);
}
.card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-primary);
  margin: 0;
}
.card-summary {
  margin: 6px 0 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--fg-secondary);
}
.card-refs {
  list-style: none;
  margin: 10px 0 0;
  padding: 8px 0 0;
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.card-ref {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--fg-muted);
}
.card-foot {
  margin-top: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.disabled-hint {
  font-size: 12px;
  color: var(--fg-muted);
}
.card-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}
</style>
