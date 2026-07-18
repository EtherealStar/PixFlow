<script setup lang="ts">
import { computed, ref } from 'vue'
import IconSend from '@/components/icons/IconSend.vue'
import MentionPopover from './MentionPopover.vue'
import type { FileIndexNode } from '@/stores/fileIndex'

defineOptions({ name: 'ChatComposer' })

/**
 * Composer — 输入框（web.md §7.3 / §十二）
 *
 * - textarea + @ 提及 + 发送按钮
 * - 圆角 2xl；bg-bg-panel；shadow-sm
 * - Ctrl+Enter 发送
 */
const props = defineProps<{
  modelValue?: string
  sending?: boolean
  streaming?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [v: string]
  send: []
  stop: []
}>()

const mentionOpen = ref(false)
const mentionQuery = ref('')
const textareaRef = ref<HTMLTextAreaElement | null>(null)

function onInput(v: string): void {
  emit('update:modelValue', v)
  const atIdx = v.lastIndexOf('@')
  if (atIdx >= 0) {
    const after = v.slice(atIdx + 1)
    // @ 后面不能有空格/换行，否则不算触发中
    if (!/\s/.test(after)) {
      mentionQuery.value = after
      mentionOpen.value = true
      return
    }
  }
  mentionOpen.value = false
}

function onSelectMention(node: FileIndexNode): void {
  const v = props.modelValue ?? ''
  const atIdx = v.lastIndexOf('@')
  if (atIdx >= 0) {
    const next = `${v.slice(0, atIdx)}@${node.label} `
    emit('update:modelValue', next)
  }
  mentionOpen.value = false
}

function onKeydown(ev: KeyboardEvent): void {
  if (ev.ctrlKey && ev.key === 'Enter') {
    ev.preventDefault()
    if (canSend.value) emit('send')
    return
  }
  if (!mentionOpen.value) return
  if (ev.key === 'Escape') {
    mentionOpen.value = false
  }
}

const canSend = computed(() => (props.modelValue ?? '').trim().length > 0 && !props.sending)
</script>

<template>
  <div
    class="composer relative flex flex-col bg-bg-panel rounded-2xl shadow-sm border border-border px-3 pt-3 pb-2 transition-colors"
  >
    <MentionPopover
      :open="mentionOpen"
      :query="mentionQuery"
      @update:open="mentionOpen = $event"
      @select="onSelectMention"
    >
      <template #trigger>
        <textarea
          ref="textareaRef"
          :value="modelValue"
          :rows="3"
          class="w-full bg-transparent text-base text-fg-primary placeholder:text-fg-muted resize-none border-0 p-0 focus:border-0 focus:ring-0 focus:outline-none"
          placeholder="输入消息...（Ctrl+Enter 发送，@ 引用文件）"
          @input="(ev: Event) => onInput((ev.target as HTMLTextAreaElement).value)"
          @keydown="onKeydown"
        />
      </template>
    </MentionPopover>

    <!-- Bottom Actions -->
    <div class="flex items-center justify-between mt-1">
      <span />

      <!-- Right: Stop + Send -->
      <div class="flex items-center gap-2">
        <button 
          v-if="streaming"
          type="button"
          class="flex items-center justify-center w-8 h-8 rounded-full bg-bg-sunken hover:bg-border text-fg-primary transition-colors"
          aria-label="停止"
          @click="$emit('stop')"
        >
          <span class="w-3 h-3 bg-fg-primary rounded-sm" />
        </button>
        <button 
          type="button"
          class="flex items-center justify-center w-8 h-8 rounded-full transition-colors"
          :class="canSend ? 'bg-fg-primary text-bg-panel hover:opacity-90' : 'bg-bg-sunken text-fg-muted cursor-not-allowed'"
          :disabled="!canSend"
          :aria-label="streaming ? '加入发送队列' : '发送'"
          @click="$emit('send')"
        >
          <IconSend :size="16" />
        </button>
      </div>
    </div>
  </div>
</template>
