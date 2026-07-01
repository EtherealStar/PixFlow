<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import ChatBubble from './ChatBubble.vue'

/**
 * MessageStream — 消息流（web.md §7.3）
 *
 * - flex flex-col gap-3
 * - 自动滚到底（新增消息 / 流式 deltas 更新时）
 */
const props = defineProps<{
  deltas: string
  userMessages?: Array<{ id: string; text: string }>
}>()

const containerRef = ref<HTMLElement | null>(null)

async function scrollToBottom(): Promise<void> {
  await nextTick()
  const el = containerRef.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(() => props.deltas, scrollToBottom)
watch(() => props.userMessages?.length, scrollToBottom)

const hasAssistantText = computed(() => props.deltas.length > 0)
</script>

<template>
  <div ref="containerRef" class="message-stream flex flex-col gap-3 p-4 overflow-y-auto">
    <ChatBubble
      v-for="m in userMessages ?? []"
      :key="m.id"
      role="user"
      :text="m.text"
    />
    <ChatBubble v-if="hasAssistantText" role="assistant" :text="deltas" />
  </div>
</template>

<style scoped>
.message-stream {
  height: 100%;
}
</style>