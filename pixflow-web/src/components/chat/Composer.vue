<script setup lang="ts">
import { computed, ref } from 'vue'
import IconSend from '@/components/icons/IconSend.vue'
import IconPaperclip from '@/components/icons/IconPaperclip.vue'
import MentionPopover from './MentionPopover.vue'
import type { FileIndexNode } from '@/stores/fileIndex'

/**
 * Composer — 输入框（web.md §7.3 / §十二）
 *
 * - textarea + @ 提及 + 上传按钮 + 发送按钮
 * - 圆角 2xl；bg-bg-panel；shadow-sm
 * - Ctrl+Enter 发送
 */
const props = defineProps<{
  modelValue: string
  sending?: boolean
  streaming?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [v: string]
  send: []
  stop: []
  attach: []
  attachFiles: [files: File[]]
}>()

const mentionOpen = ref(false)
const mentionQuery = ref('')
const textareaRef = ref<HTMLTextAreaElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const isDragging = ref(false)

function onDragOver(e: DragEvent) {
  isDragging.value = true
}
function onDragLeave(e: DragEvent) {
  isDragging.value = false
}
function onDrop(e: DragEvent) {
  isDragging.value = false
  if (e.dataTransfer?.files && e.dataTransfer.files.length > 0) {
    emit('attachFiles', Array.from(e.dataTransfer.files))
  }
}

function triggerFileSelect() {
  fileInput.value?.click()
}

function onFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  if (target.files && target.files.length > 0) {
    emit('attachFiles', Array.from(target.files))
    target.value = ''
  }
}

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
  const v = props.modelValue
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
    emit('send')
    return
  }
  if (!mentionOpen.value) return
  if (ev.key === 'Escape') {
    mentionOpen.value = false
  }
}

const canSend = computed(() => props.modelValue.trim().length > 0 && !props.sending)
</script>

<template>
  <div 
    class="composer relative flex flex-col bg-bg-panel rounded-2xl shadow-sm border border-border px-3 pt-3 pb-2 transition-colors"
    @dragover.prevent="onDragOver"
    @dragleave.prevent="onDragLeave"
    @drop.prevent="onDrop"
  >
    <!-- Drag overlay -->
    <div v-if="isDragging" class="absolute inset-0 z-10 flex items-center justify-center bg-bg-panel/90 rounded-2xl border-2 border-dashed border-accent pointer-events-none">
      <span class="text-accent font-medium">松开鼠标以上传文件</span>
    </div>

    <!-- Hidden file input -->
    <input type="file" ref="fileInput" class="hidden" multiple @change="onFileChange" />
    <MentionPopover :open="mentionOpen" :query="mentionQuery" @update:open="mentionOpen = $event" @select="onSelectMention">
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
      <!-- Left: Attach -->
      <button 
        type="button" 
        class="flex items-center justify-center text-fg-muted hover:text-fg-primary hover:bg-bg-sunken w-8 h-8 rounded-lg transition-colors"
        @click="triggerFileSelect"
        aria-label="上传附件"
      >
        <IconPaperclip :size="20" />
      </button>

      <!-- Right: Send / Stop -->
      <button 
        v-if="streaming"
        type="button"
        class="flex items-center justify-center w-8 h-8 rounded-full bg-bg-sunken hover:bg-border text-fg-primary transition-colors"
        @click="$emit('stop')"
        aria-label="停止"
      >
        <span class="w-3 h-3 bg-fg-primary rounded-sm"></span>
      </button>
      <button 
        v-else
        type="button"
        class="flex items-center justify-center w-8 h-8 rounded-full transition-colors"
        :class="canSend ? 'bg-fg-primary text-bg-panel hover:opacity-90' : 'bg-bg-sunken text-fg-muted cursor-not-allowed'"
        :disabled="!canSend"
        @click="$emit('send')"
        aria-label="发送"
      >
        <IconSend :size="16" />
      </button>
    </div>
  </div>
</template>