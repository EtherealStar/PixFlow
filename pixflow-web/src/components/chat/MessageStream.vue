<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { TimelineItem, ToolTimelineItem, TransitionTimelineItem } from '@/types/agent'
import ChatBubble from './ChatBubble.vue'

/**
 * MessageStream — 消息流（web.md §7.3）
 *
 * - flex flex-col gap-3
 * - 自动滚到底（新增消息 / timeline 更新时）
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

function toolStatusText(status: ToolTimelineItem['status']): string {
  switch (status) {
    case 'queued': return '等待执行'
    case 'running': return '执行中'
    case 'completed': return '完成'
    case 'error': return '失败'
  }
}

function toolStatusClass(status: ToolTimelineItem['status']): string {
  switch (status) {
    case 'queued': return 'bg-bg-muted text-fg-secondary border-border'
    case 'running': return 'bg-blue-50 text-blue-700 border-blue-200'
    case 'completed': return 'bg-emerald-50 text-emerald-700 border-emerald-200'
    case 'error': return 'bg-red-50 text-red-700 border-red-200'
  }
}

function summarizeToolResult(item: ToolTimelineItem): string {
  if (!item.result) return ''
  return item.result.length <= 180 ? item.result : `${item.result.slice(0, 180)}...`
}

function transitionText(item: TransitionTimelineItem): string {
  if (item.reason === 'RATE_LIMIT_RETRY') {
    if (typeof item.attempt === 'number') {
      return `模型连接中断，正在第 ${item.attempt} 次重试`
    }
    return '模型连接中断，正在重试'
  }
  return item.reason
}
</script>

<template>
  <div
    ref="containerRef"
    class="message-stream flex flex-col gap-3 p-4 overflow-y-auto"
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
      />

      <div
        v-else-if="item.type === 'tool'"
        class="tool-item ml-2 max-w-[82%] rounded-lg border border-border bg-bg-panel px-3 py-2 text-sm shadow-sm"
      >
        <div class="flex items-center gap-2">
          <span class="font-medium text-fg-primary">{{ item.toolName }}</span>
          <span
            class="rounded-full border px-2 py-0.5 text-xs"
            :class="toolStatusClass(item.status)"
          >
            {{ toolStatusText(item.status) }}
          </span>
        </div>
        <pre
          v-if="item.input !== undefined"
          class="mt-2 max-h-24 overflow-auto whitespace-pre-wrap rounded bg-bg-muted px-2 py-1 text-xs text-fg-secondary"
        >{{ JSON.stringify(item.input, null, 2) }}</pre>
        <p
          v-if="summarizeToolResult(item)"
          class="mt-2 whitespace-pre-wrap text-fg-secondary"
        >
          {{ summarizeToolResult(item) }}
        </p>
      </div>

      <div
        v-else-if="item.type === 'transition' && item.reason === 'RATE_LIMIT_RETRY'"
        class="ml-2 max-w-[82%] rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800"
      >
        {{ transitionText(item) }}
        <span
          v-if="item.errorCode"
          class="ml-2 text-xs text-amber-700"
        >{{ item.errorCode }}</span>
      </div>

      <div
        v-else-if="item.type === 'error'"
        class="max-w-[82%] rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
      >
        {{ item.message }}
      </div>
    </template>
  </div>
</template>

<style scoped>
.message-stream {
  height: 100%;
}
</style>
