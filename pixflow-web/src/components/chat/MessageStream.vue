<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { TimelineItem, ToolTimelineItem, TransitionTimelineItem, ErrorTimelineItem } from '@/types/agent'
import ChatBubble from './ChatBubble.vue'
import IconLoader from '@/components/icons/IconLoader.vue'
import IconCheck from '@/components/icons/IconCheck.vue'
import IconAlertCircle from '@/components/icons/IconAlertCircle.vue'
import IconCopy from '@/components/icons/IconCopy.vue'
import IconChevronRight from '@/components/icons/IconChevronRight.vue'

/**
 * MessageStream — 消息流（frontend/chat.md）
 *
 * - 用户消息 + 助手时间线（文本 / 工具状态 / 关键转场 / 错误）
 * - 工具事件只渲染产品语言摘要；原始工具名、参数、结果永不上界面
 * - 错误可附可复制的错误编号（trace ID 唯一的合法曝光位）
 */
const props = defineProps<{
  timeline: TimelineItem[]
  userMessages?: Array<{ id: string; text: string }>
}>()

const containerRef = ref<HTMLElement | null>(null)

async function scrollToBottom(): Promise<void> {
  await nextTick()
  const el = containerRef.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(() => props.timeline, scrollToBottom, { deep: true })
watch(() => props.userMessages?.length, scrollToBottom)

const toolStatusText: Record<ToolTimelineItem['status'], string> = {
  QUEUED: '排队中',
  RUNNING: '处理中',
  SUCCEEDED: '已成功',
  FAILED: '已失败',
}

function transitionText(item: TransitionTimelineItem): string {
  return item.state ? `${item.label} · ${item.state}` : item.label
}

/* 错误编号复制 */
const copiedId = ref<string | null>(null)
async function copyErrorNumber(item: ErrorTimelineItem): Promise<void> {
  if (!item.traceId) return
  try {
    await navigator.clipboard.writeText(item.traceId)
    copiedId.value = item.id
    setTimeout(() => { if (copiedId.value === item.id) copiedId.value = null }, 2000)
  } catch {
    // 剪贴板不可用时静默降级：编号仍可选择复制
  }
}
</script>

<template>
  <div
    ref="containerRef"
    class="message-stream flex flex-col gap-4 px-6 py-6"
  >
    <ChatBubble
      v-for="m in userMessages ?? []"
      :key="m.id"
      role="user"
      :text="m.text"
    />

    <template
      v-for="item in timeline"
      :key="item.id"
    >
      <ChatBubble
        v-if="item.type === 'assistant' && item.text"
        role="assistant"
        :text="item.text"
        :status="item.status"
      />

      <!-- 工具状态：一行安静的状态摘要，不用卡片 -->
      <div
        v-else-if="item.type === 'tool'"
        class="tool-item"
        :class="`tone-${item.status.toLowerCase()}`"
      >
        <IconLoader
          v-if="item.status === 'RUNNING'"
          :size="13"
          class="tool-icon spinning"
        />
        <IconCheck
          v-else-if="item.status === 'SUCCEEDED'"
          :size="13"
          class="tool-icon"
        />
        <IconAlertCircle
          v-else-if="item.status === 'FAILED'"
          :size="13"
          class="tool-icon"
        />
        <span
          v-else
          class="tool-dot"
          aria-hidden="true"
        />
        <span class="tool-label">{{ item.label }}</span>
        <span class="tool-status">{{ toolStatusText[item.status] }}</span>
      </div>

      <!-- 关键转场：极轻的辅助行 -->
      <div
        v-else-if="item.type === 'transition'"
        class="transition-item"
      >
        <IconChevronRight :size="12" />
        <span>{{ transitionText(item) }}</span>
      </div>

      <!-- 错误：danger-soft + 可复制错误编号 -->
      <div
        v-else-if="item.type === 'error'"
        class="error-item"
        role="alert"
      >
        <div class="error-main">
          <IconAlertCircle
            :size="14"
            class="error-icon"
          />
          <span class="error-message">{{ item.message }}</span>
        </div>
        <div
          v-if="item.traceId"
          class="error-number"
        >
          <span class="error-number-label">错误编号：{{ item.traceId }}</span>
          <button
            type="button"
            class="copy-btn"
            :aria-label="copiedId === item.id ? '已复制' : '复制错误编号'"
            @click="copyErrorNumber(item)"
          >
            <IconCheck
              v-if="copiedId === item.id"
              :size="12"
            />
            <IconCopy
              v-else
              :size="12"
            />
            {{ copiedId === item.id ? '已复制' : '复制' }}
          </button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.message-stream {
  width: 100%;
  max-width: 768px;
  margin: 0 auto;
}

/* 工具状态行 */
.tool-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px;
  font-size: 13px;
  color: var(--fg-secondary);
}
.tool-icon {
  flex-shrink: 0;
}
.tool-icon.spinning {
  animation: tool-spin 1s linear infinite;
}
@keyframes tool-spin {
  to { transform: rotate(360deg); }
}
.tool-dot {
  width: 6px;
  height: 6px;
  border-radius: 9999px;
  background: var(--fg-muted);
  flex-shrink: 0;
  margin: 0 3.5px;
}
.tool-label {
  font-weight: 500;
  color: var(--fg-primary);
}
.tool-status {
  color: var(--fg-muted);
}
.tool-item.tone-running .tool-icon,
.tool-item.tone-running .tool-status {
  color: var(--info);
}
.tool-item.tone-succeeded .tool-icon,
.tool-item.tone-succeeded .tool-status {
  color: var(--success);
}
.tool-item.tone-failed .tool-icon,
.tool-item.tone-failed .tool-status {
  color: var(--danger);
}

/* 转场辅助行 */
.transition-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 10px;
  font-size: 12px;
  color: var(--fg-muted);
}

/* 错误块 */
.error-item {
  max-width: 82%;
  border-radius: 10px;
  background: var(--danger-soft);
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.error-main {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.error-icon {
  color: var(--danger);
  flex-shrink: 0;
  margin-top: 2px;
}
.error-message {
  font-size: 13px;
  color: var(--fg-primary);
  line-height: 1.5;
}
.error-number {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-left: 22px;
}
.error-number-label {
  font-size: 12px;
  color: var(--fg-secondary);
  user-select: all;
}
.copy-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: transparent;
  border: none;
  padding: 2px 4px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-secondary);
  cursor: pointer;
  flex-shrink: 0;
  transition: color var(--dur-fast) var(--ease-out), background-color var(--dur-fast) var(--ease-out);
}
.copy-btn:hover {
  color: var(--fg-primary);
  background: var(--bg-panel);
}
</style>
