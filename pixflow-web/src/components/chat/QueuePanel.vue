<script setup lang="ts">
import { ref } from 'vue'
import type { MessageReference } from '@/api/messages'
import type { QueuedMessageView } from '@/runtime/useAgentTurn'
import IconAlertCircle from '@/components/icons/IconAlertCircle.vue'

/**
 * QueuePanel — 每会话排队消息面板（frontend/chat.md）
 *
 * - 固定在 Composer 正上方；FIFO 顺序即发送顺序
 * - 编辑就地完成，保留队列位置
 * - 失败暂停时给出「继续」恢复入口
 */
withDefaults(
  defineProps<{
    items: QueuedMessageView[]
    paused: boolean
    /** 有待决定的提案时，自动派发暂停 */
    waitingForProposal?: boolean
  }>(),
  { waitingForProposal: false }
)

const emit = defineEmits<{
  'begin-edit': [id: string]
  'save-edit': [id: string, prompt: string, references: MessageReference[]]
  'cancel-edit': [id: string]
  cancel: [id: string]
  continue: []
}>()

const editingId = ref<string | null>(null)
const editPrompt = ref('')
const editReferences = ref<MessageReference[]>([])

function begin(item: QueuedMessageView): void {
  editingId.value = item.id
  editPrompt.value = item.prompt
  editReferences.value = [...item.references]
  emit('begin-edit', item.id)
}

function save(id: string): void {
  if (!editPrompt.value.trim() && editReferences.value.length === 0) return
  emit('save-edit', id, editPrompt.value, editReferences.value)
  editingId.value = null
}

function cancelEdit(id: string): void {
  emit('cancel-edit', id)
  editingId.value = null
}
</script>

<template>
  <section
    v-if="items.length"
    class="queue-panel"
    aria-label="排队消息"
  >
    <div class="queue-head">
      <span class="queue-title">排队中 {{ items.length }}</span>
      <span
        v-if="waitingForProposal"
        class="queue-hint"
      >等待提案决定后自动发送</span>
    </div>

    <div
      v-if="paused"
      class="queue-paused"
      role="status"
    >
      <IconAlertCircle
        :size="13"
        class="shrink-0"
      />
      <span class="flex-1">发送中断，队列已暂停</span>
      <button
        type="button"
        class="continue-btn"
        @click="emit('continue')"
      >
        继续
      </button>
    </div>

    <ol class="queue-list">
      <li
        v-for="(item, index) in items"
        :key="item.id"
        class="queue-item"
      >
        <span class="queue-index tabular-nums">{{ index + 1 }}</span>

        <template v-if="editingId === item.id">
          <div class="queue-edit">
            <textarea
              v-model="editPrompt"
              rows="2"
              class="edit-textarea"
              aria-label="编辑排队消息"
            />
            <div
              v-if="editReferences.length"
              class="edit-refs"
            >
              <button
                v-for="reference in editReferences"
                :key="reference.referenceKey"
                type="button"
                class="ref-chip"
                :title="`移除 ${reference.displayPathSnapshot}`"
                @click="editReferences = editReferences.filter((value) => value.referenceKey !== reference.referenceKey)"
              >
                {{ reference.displayPathSnapshot }} ×
              </button>
            </div>
            <div class="edit-actions">
              <button
                type="button"
                class="text-btn"
                @click="cancelEdit(item.id)"
              >
                返回
              </button>
              <button
                type="button"
                class="text-btn strong"
                @click="save(item.id)"
              >
                保存
              </button>
            </div>
          </div>
        </template>

        <template v-else>
          <div class="queue-content">
            <p class="queue-prompt">
              {{ item.prompt || '仅引用素材' }}
            </p>
            <p
              v-if="item.references.length"
              class="queue-refs"
            >
              {{ item.references.map((reference) => reference.displayPathSnapshot).join('、') }}
            </p>
          </div>
          <div class="queue-actions">
            <button
              type="button"
              class="text-btn"
              @click="begin(item)"
            >
              编辑
            </button>
            <button
              type="button"
              class="text-btn danger"
              @click="emit('cancel', item.id)"
            >
              取消
            </button>
          </div>
        </template>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.queue-panel {
  width: 100%;
  max-width: 768px;
  margin: 0 auto 8px;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 10px 12px;
  box-shadow: var(--shadow-sm);
}
.queue-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.queue-title {
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-secondary);
}
.queue-hint {
  font-size: 12px;
  color: var(--fg-muted);
}
.queue-paused {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  background: var(--warning-soft);
  font-size: 12px;
  color: var(--fg-primary);
}
.continue-btn {
  background: transparent;
  border: none;
  padding: 0;
  font-size: 12px;
  font-weight: 500;
  color: var(--accent);
  cursor: pointer;
}
.continue-btn:hover {
  text-decoration: underline;
}
.queue-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.queue-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 10px;
  background: var(--bg-sunken);
}
.queue-index {
  flex-shrink: 0;
  width: 16px;
  margin-top: 1px;
  font-size: 12px;
  color: var(--fg-muted);
  text-align: center;
}
.queue-content {
  flex: 1;
  min-width: 0;
}
.queue-prompt {
  margin: 0;
  font-size: 13px;
  color: var(--fg-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.queue-refs {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--fg-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.queue-actions {
  display: flex;
  gap: 10px;
  flex-shrink: 0;
}
.text-btn {
  background: transparent;
  border: none;
  padding: 0;
  font-size: 12px;
  font-weight: 500;
  color: var(--fg-secondary);
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease-out);
}
.text-btn:hover {
  color: var(--fg-primary);
}
.text-btn.strong {
  color: var(--accent);
}
.text-btn.strong:hover {
  color: var(--accent-hover);
}
.text-btn.danger:hover {
  color: var(--danger);
}
.queue-edit {
  flex: 1;
  min-width: 0;
}
.edit-textarea {
  width: 100%;
  resize: none;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-panel);
  padding: 6px 8px;
  font-size: 13px;
  color: var(--fg-primary);
  font-family: inherit;
}
.edit-textarea:focus-visible {
  outline: none;
  border-color: var(--accent);
  box-shadow: 0 0 0 2px var(--accent-ring);
}
.edit-refs {
  margin-top: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.ref-chip {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: none;
  border-radius: 6px;
  background: var(--accent-soft);
  padding: 2px 8px;
  font-size: 12px;
  color: var(--fg-primary);
  cursor: pointer;
}
.edit-actions {
  margin-top: 6px;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>
