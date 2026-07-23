<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import type { ActivityView } from '@/api/activities'
import AppBadge from '@/components/ui/AppBadge.vue'
import AppProgressBar from '@/components/ui/AppProgressBar.vue'
import IconChevronDown from '@/components/icons/IconChevronDown.vue'
import IconExternalLink from '@/components/icons/IconExternalLink.vue'

/**
 * ActivityCard — 全局 Activity 记录卡片（frontend/tasks.md）
 *
 * - 状态文案只用规范术语（上传中 / 解析中 / 排队中 / 处理中 / 已成功 / 部分成功 / 已失败）
 * - 原始枚举值与资源 ID 不上界面；跳转走显式链接（会话 / 素材包 / 产物）
 * - 详情在面板内展开：时间线与进度明细
 */
const props = defineProps<{
  activity: ActivityView
  busy?: boolean
}>()

const emit = defineEmits<{
  cancel: []
  retry: []
  clear: []
}>()

const kindLabel = computed(() => ({
  UPLOAD: '素材上传',
  PROCESS: '图片处理',
  IMAGEGEN: '图片生成'
})[props.activity.kind])

const statusPresentation = computed(() => ({
  UPLOADING: { label: '上传中', tone: 'info' as const },
  EXTRACTING: { label: '解析中', tone: 'info' as const },
  QUEUED: { label: '排队中', tone: 'neutral' as const },
  RUNNING: { label: '处理中', tone: 'info' as const },
  SUCCEEDED: { label: '已成功', tone: 'success' as const },
  PARTIALLY_SUCCEEDED: { label: '部分成功', tone: 'warning' as const },
  FAILED: { label: '已失败', tone: 'danger' as const }
})[props.activity.status])

const isActive = computed(() => ['UPLOADING', 'EXTRACTING', 'QUEUED', 'RUNNING'].includes(props.activity.status))

const percent = computed(() => {
  if (props.activity.progress.total === 0) return 0
  return Math.round(props.activity.progress.completed / props.activity.progress.total * 100)
})

const showProgress = computed(() => props.activity.progress.total > 0)

const progressTone = computed(() => {
  if (props.activity.status === 'FAILED') return 'danger' as const
  if (props.activity.status === 'PARTIALLY_SUCCEEDED') return 'warning' as const
  if (props.activity.status === 'SUCCEEDED') return 'success' as const
  return 'accent' as const
})

/* 显式导航链接（product.md：conversation / package / output links are explicit actions） */
const conversationLink = computed(() =>
  props.activity.conversationId ? `/chat/${props.activity.conversationId}` : null)
const packageLink = computed(() =>
  props.activity.packageId ? `/materials/packages/${props.activity.packageId}` : null)
const outputLink = computed(() =>
  props.activity.conversationId && props.activity.taskId
    ? `/outputs/conversations/${props.activity.conversationId}/tasks/${props.activity.taskId}`
    : null)

const detailOpen = ref(false)

function formatTime(value: string | null | undefined): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return '—'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}月${d.getDate()}日 ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const hasActions = computed(() =>
  props.activity.allowedActions.cancel ||
  props.activity.allowedActions.retryFailed ||
  props.activity.allowedActions.clear)
</script>

<template>
  <article class="activity-card">
    <div class="card-head">
      <div class="min-w-0">
        <h3 class="card-title">
          {{ kindLabel }}
        </h3>
        <div class="card-links">
          <RouterLink
            v-if="conversationLink"
            :to="conversationLink"
            class="card-link"
          >
            查看会话
            <IconExternalLink :size="11" />
          </RouterLink>
          <RouterLink
            v-if="packageLink"
            :to="packageLink"
            class="card-link"
          >
            查看素材包
            <IconExternalLink :size="11" />
          </RouterLink>
          <RouterLink
            v-if="outputLink"
            :to="outputLink"
            class="card-link"
          >
            查看产物
            <IconExternalLink :size="11" />
          </RouterLink>
        </div>
      </div>
      <AppBadge
        :tone="statusPresentation.tone"
        style="soft"
      >
        {{ statusPresentation.label }}
      </AppBadge>
    </div>

    <div
      v-if="showProgress"
      class="card-progress"
    >
      <AppProgressBar
        :percent="percent"
        :tone="progressTone"
      />
      <div class="progress-meta">
        <span class="tabular-nums">{{ activity.progress.completed }}/{{ activity.progress.total }}</span>
        <span
          v-if="activity.progress.failed > 0"
          class="progress-failed tabular-nums"
        >失败 {{ activity.progress.failed }}</span>
      </div>
    </div>

    <div class="card-actions">
      <button
        v-if="activity.allowedActions.retryFailed"
        type="button"
        class="action-btn strong"
        :disabled="busy"
        @click="emit('retry')"
      >
        重试失败项
      </button>
      <button
        v-if="activity.allowedActions.cancel"
        type="button"
        class="action-btn"
        :disabled="busy"
        @click="emit('cancel')"
      >
        取消
      </button>
      <button
        v-if="activity.allowedActions.clear"
        type="button"
        class="action-btn"
        :disabled="busy"
        @click="emit('clear')"
      >
        清除
      </button>
      <button
        type="button"
        class="action-btn detail-toggle"
        :class="{ 'ml-auto': !hasActions }"
        :aria-expanded="detailOpen"
        :aria-label="detailOpen ? '收起详情' : '展开详情'"
        @click="detailOpen = !detailOpen"
      >
        详情
        <IconChevronDown
          :size="12"
          class="detail-chevron"
          :class="{ open: detailOpen }"
        />
      </button>
    </div>

    <dl
      v-if="detailOpen"
      class="card-detail"
    >
      <div class="detail-row">
        <dt>创建</dt>
        <dd>{{ formatTime(activity.createdAt) }}</dd>
      </div>
      <div class="detail-row">
        <dt>开始</dt>
        <dd>{{ formatTime(activity.startedAt) }}</dd>
      </div>
      <div class="detail-row">
        <dt>完成</dt>
        <dd>{{ formatTime(activity.finishedAt) }}</dd>
      </div>
    </dl>

    <div
      v-if="isActive"
      class="sr-only"
      role="status"
    >
      {{ kindLabel }}{{ statusPresentation.label }}
    </div>
  </article>
</template>

<style scoped>
.activity-card {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 12px;
  box-shadow: var(--shadow-sm);
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}
.card-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--fg-primary);
  margin: 0;
}
.card-links {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin-top: 4px;
}
.card-link {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 12px;
  color: var(--accent);
  text-decoration: none;
}
.card-link:hover {
  text-decoration: underline;
}

.card-progress {
  margin-top: 10px;
}
.progress-meta {
  margin-top: 4px;
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--fg-muted);
}
.progress-failed {
  color: var(--danger);
}

.card-actions {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: center;
  gap: 4px 12px;
}
.action-btn {
  background: transparent;
  border: none;
  padding: 2px 0;
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-secondary);
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease-out);
}
.action-btn:hover:not(:disabled) {
  color: var(--fg-primary);
  text-decoration: underline;
}
.action-btn.strong {
  color: var(--accent);
}
.action-btn.strong:hover:not(:disabled) {
  color: var(--accent-hover);
}
.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.detail-toggle {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--fg-muted);
}
.detail-chevron {
  transition: transform var(--dur-fast) var(--ease-out);
}
.detail-chevron.open {
  transform: rotate(180deg);
}

.card-detail {
  margin: 10px 0 0;
  padding: 8px 0 0;
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.detail-row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}
.detail-row dt {
  color: var(--fg-muted);
}
.detail-row dd {
  margin: 0;
  color: var(--fg-secondary);
}
</style>
