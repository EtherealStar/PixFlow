<script setup lang="ts">
import { computed, ref } from 'vue'
import IconSend from '@/components/icons/IconSend.vue'
import IconPaperclip from '@/components/icons/IconPaperclip.vue'
import MentionPopover from './MentionPopover.vue'
import { useToastStore } from '@/stores/toast'
import type { AssetReferenceCandidate } from '@/api/assetReferences'
import type { MessageReference } from '@/api/messages'

defineOptions({ name: 'ChatComposer' })

/**
 * Composer — 结构化输入框（frontend/chat.md）
 *
 * - Enter 发送 / Shift+Enter 换行 / Ctrl+Enter 发送
 * - 回形针打开多选文件选择器，仅 .zip / .rar / .7z；
 *   非压缩包在计算哈希前拒绝，文案固定为「只能上传压缩文件」
 * - @ 提及为原子 token；上传承接由 ChatPage 完成，进度只出现在活动面板
 */
const props = defineProps<{
  modelValue?: string
  sending?: boolean
  streaming?: boolean
  references: MessageReference[]
}>()

const emit = defineEmits<{
  'update:modelValue': [v: string]
  'update:references': [references: MessageReference[]]
  send: []
  stop: []
  upload: [files: File[]]
}>()

const toast = useToastStore()

const mentionOpen = ref(false)
const mentionQuery = ref('')
const mentionRef = ref<InstanceType<typeof MentionPopover> | null>(null)
const textareaRef = ref<HTMLTextAreaElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

const ARCHIVE_PATTERN = /\.(zip|rar|7z)$/i

function isArchive(file: File): boolean {
  return ARCHIVE_PATTERN.test(file.name)
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

function onSelectMention(node: AssetReferenceCandidate): void {
  if (props.references.length >= 20) return
  const v = props.modelValue ?? ''
  const atIdx = v.lastIndexOf('@')
  if (atIdx >= 0) {
    emit('update:modelValue', v.slice(0, atIdx))
  }
  emit('update:references', [...props.references, {
    referenceKey: node.referenceKey,
    displayPathSnapshot: node.displayPath
  }])
  mentionOpen.value = false
}

function removeReference(referenceKey: string): void {
  emit('update:references', props.references.filter((reference) => reference.referenceKey !== referenceKey))
}

function onKeydown(ev: KeyboardEvent): void {
  /* 提及面板打开时：↑↓ 移动选择 / Enter 确认 / Esc 关闭（web.md §十二） */
  if (mentionOpen.value) {
    if (ev.key === 'ArrowDown') {
      ev.preventDefault()
      mentionRef.value?.moveActive(1)
      return
    }
    if (ev.key === 'ArrowUp') {
      ev.preventDefault()
      mentionRef.value?.moveActive(-1)
      return
    }
    if (ev.key === 'Enter' && !ev.shiftKey) {
      ev.preventDefault()
      mentionRef.value?.confirmActive()
      return
    }
    if (ev.key === 'Escape') {
      mentionOpen.value = false
      return
    }
  }
  if (ev.key === 'Enter' && (ev.ctrlKey || ev.metaKey)) {
    ev.preventDefault()
    if (canSend.value) emit('send')
    return
  }
  if (ev.key === 'Enter' && !ev.shiftKey) {
    ev.preventDefault()
    if (canSend.value) emit('send')
  }
}

function pickArchives(): void {
  fileInputRef.value?.click()
}

function onFilesPicked(ev: Event): void {
  const target = ev.target as HTMLInputElement
  if (!target.files || target.files.length === 0) return
  const files = Array.from(target.files)
  target.value = ''
  const valid = files.filter(isArchive)
  if (valid.length < files.length) {
    toast.push({ variant: 'danger', message: '只能上传压缩文件' })
  }
  if (valid.length > 0) emit('upload', valid)
}

const canSend = computed(() => ((props.modelValue ?? '').trim().length > 0 || props.references.length > 0) && !props.sending)
</script>

<template>
  <div class="composer">
    <div
      v-if="references.length"
      class="ref-row"
    >
      <span
        v-for="reference in references"
        :key="reference.referenceKey"
        class="ref-token"
      >
        <span class="truncate">{{ reference.displayPathSnapshot }}</span>
        <button
          type="button"
          class="ref-remove"
          :aria-label="`移除 ${reference.displayPathSnapshot}`"
          @click="removeReference(reference.referenceKey)"
        >×</button>
      </span>
    </div>

    <MentionPopover
      ref="mentionRef"
      :open="mentionOpen"
      :query="mentionQuery"
      :excluded-reference-keys="references.map((reference) => reference.referenceKey)"
      @update:open="mentionOpen = $event"
      @select="onSelectMention"
    >
      <template #trigger>
        <textarea
          ref="textareaRef"
          :value="modelValue"
          :rows="2"
          class="composer-input"
          placeholder="描述要处理的图片任务，@ 引用素材或产物"
          aria-label="消息输入框"
          @input="(ev: Event) => onInput((ev.target as HTMLTextAreaElement).value)"
          @keydown="onKeydown"
        />
      </template>
    </MentionPopover>

    <div class="composer-bar">
      <button
        type="button"
        class="attach-btn"
        aria-label="上传素材包"
        title="上传素材包（.zip / .rar / .7z）"
        @click="pickArchives"
      >
        <IconPaperclip :size="16" />
      </button>
      <input
        ref="fileInputRef"
        type="file"
        multiple
        accept=".zip,.rar,.7z"
        class="hidden"
        aria-hidden="true"
        tabindex="-1"
        @change="onFilesPicked"
      >

      <span class="bar-hint">Enter 发送 · Shift+Enter 换行</span>

      <div class="bar-actions">
        <button
          v-if="streaming"
          type="button"
          class="stop-btn"
          aria-label="停止生成"
          title="停止生成"
          @click="$emit('stop')"
        >
          <span class="stop-square" />
        </button>
        <button
          type="button"
          class="send-btn"
          :class="{ ready: canSend }"
          :disabled="!canSend"
          :aria-label="streaming ? '加入发送队列' : '发送'"
          :title="streaming ? '加入发送队列' : '发送'"
          @click="$emit('send')"
        >
          <IconSend :size="15" />
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.composer {
  width: 100%;
  max-width: 768px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 14px;
  box-shadow: var(--shadow-sm);
  padding: 10px 12px 8px;
  transition: border-color var(--dur-fast) var(--ease-out), box-shadow var(--dur-fast) var(--ease-out);
}
/* 焦点落在壳上（DESIGN.md 输入框规范） */
.composer:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-ring), var(--shadow-sm);
}

.ref-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.ref-token {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  background: var(--accent-soft);
  border-radius: 6px;
  padding: 2px 6px 2px 8px;
  font-size: 12px;
  color: var(--fg-primary);
}
.ref-remove {
  background: transparent;
  border: none;
  padding: 0 2px;
  line-height: 1;
  color: var(--fg-muted);
  cursor: pointer;
  border-radius: 6px;
}
.ref-remove:hover {
  color: var(--fg-primary);
}

.composer-input {
  width: 100%;
  background: transparent;
  border: none;
  padding: 2px 0;
  resize: none;
  font-size: 14px;
  line-height: 1.6;
  color: var(--fg-primary);
  font-family: inherit;
}
.composer-input::placeholder {
  color: var(--fg-muted);
}
.composer-input:focus {
  outline: none;
}

.composer-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}
.attach-btn {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: transparent;
  border: none;
  color: var(--fg-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background-color var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);
}
.attach-btn:hover {
  background: var(--bg-sunken);
  color: var(--fg-primary);
}
.bar-hint {
  font-size: 12px;
  color: var(--fg-muted);
  user-select: none;
}
.bar-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
.stop-btn {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: var(--bg-sunken);
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background-color var(--dur-fast) var(--ease-out);
}
.stop-btn:hover {
  background: var(--border);
}
/* 停止符号为锐角实心方块（■），0 非圆角档位 */
.stop-square {
  width: 12px;
  height: 12px;
  background: var(--fg-primary);
}
.send-btn {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-sunken);
  color: var(--fg-muted);
  cursor: not-allowed;
  transition: background-color var(--dur-fast) var(--ease-out), transform var(--dur-fast) var(--ease-out);
}
.send-btn.ready {
  background: var(--accent);
  color: var(--fg-inverse);
  cursor: pointer;
}
.send-btn.ready:hover {
  background: var(--accent-hover);
}
.send-btn.ready:active {
  transform: scale(0.96);
}
</style>
