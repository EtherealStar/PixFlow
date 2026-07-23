<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useConversationsStore } from '@/stores/conversations'
import { useToastStore } from '@/stores/toast'
import IconChat from '@/components/icons/IconChat.vue'
import IconMoreHorizontal from '@/components/icons/IconMoreHorizontal.vue'
import AppDialog from '@/components/ui/AppDialog.vue'
import AppButton from '@/components/ui/AppButton.vue'
import AppDropdownMenu from '@/components/ui/AppDropdownMenu.vue'
import AppDropdownMenuItem from '@/components/ui/AppDropdownMenuItem.vue'

/**
 * HistoryList — 侧边栏按日期分组的会话列表（frontend/product.md）
 *
 * - 当前会话高亮落 accent-soft
 * - 删除走 PixFlow 确认对话框（不用原生 confirm）
 */
const conversations = useConversationsStore()
const router = useRouter()
const route = useRoute()
const toast = useToastStore()

onMounted(() => {
  if (conversations.items.length === 0) {
    void conversations.refresh().catch(showError)
  }
})

const activeId = computed(() => (route.params.conversationId as string) || '')

const pendingDelete = ref<{ id: string; title: string } | null>(null)
const deleting = ref(false)

function formatGroupDate(dateString: string): string {
  const d = new Date(dateString)
  const today = new Date()
  const yesterday = new Date(today)
  yesterday.setDate(yesterday.getDate() - 1)

  const isSameDate = (d1: Date, d2: Date) =>
    d1.getFullYear() === d2.getFullYear() &&
    d1.getMonth() === d2.getMonth() &&
    d1.getDate() === d2.getDate()

  if (isSameDate(d, today)) return '今天'
  if (isSameDate(d, yesterday)) return '昨天'
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

const groupedHistory = computed(() => {
  const groups = new Map<string, typeof conversations.items>()
  for (const item of conversations.items) {
    const key = formatGroupDate(item.updatedAt)
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key)!.push(item)
  }
  return Array.from(groups.entries()).map(([label, items]) => ({ label, items }))
})

function onSelect(id: string): void {
  void router.push(`/chat/${id}`).catch(showError)
}

function showError(error: unknown): void {
  toast.push({ variant: 'danger', message: error instanceof Error ? error.message : '操作失败' })
}

function askRemove(id: string, title?: string): void {
  pendingDelete.value = { id, title: title || '未命名会话' }
}

async function confirmRemove(): Promise<void> {
  if (!pendingDelete.value || deleting.value) return
  deleting.value = true
  try {
    await conversations.remove(pendingDelete.value.id)
    toast.push({ variant: 'info', message: '已删除会话' })
    pendingDelete.value = null
  } catch (error: unknown) {
    showError(error)
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <div class="history-list">
    <div
      v-for="group in groupedHistory"
      :key="group.label"
      class="history-group"
    >
      <div class="group-label">
        {{ group.label }}
      </div>
      <ul class="group-items">
        <li
          v-for="item in group.items"
          :key="item.conversationId"
          class="history-item group/item"
          :class="{ active: activeId === item.conversationId }"
          role="button"
          tabindex="0"
          @click="onSelect(item.conversationId)"
          @keydown.enter="onSelect(item.conversationId)"
        >
          <IconChat
            :size="15"
            class="item-icon"
          />
          <span class="item-title flex-1 truncate">{{ item.title || '新会话' }}</span>

          <div class="item-actions">
            <AppDropdownMenu>
              <template #trigger>
                <button
                  type="button"
                  class="action-btn"
                  aria-label="更多操作"
                  @click.stop
                >
                  <IconMoreHorizontal :size="15" />
                </button>
              </template>
              <AppDropdownMenuItem
                danger
                @select="askRemove(item.conversationId, item.title)"
              >
                删除
              </AppDropdownMenuItem>
            </AppDropdownMenu>
          </div>
        </li>
      </ul>
    </div>

    <AppDialog
      :open="pendingDelete !== null"
      title="删除会话"
      :description="`将删除会话「${pendingDelete?.title ?? ''}」，此操作不可恢复。`"
      @update:open="(v: boolean) => { if (!v) pendingDelete = null }"
    >
      <template #footer>
        <AppButton
          variant="secondary"
          @click="pendingDelete = null"
        >
          取消
        </AppButton>
        <AppButton
          variant="danger"
          :loading="deleting"
          @click="confirmRemove"
        >
          删除
        </AppButton>
      </template>
    </AppDialog>
  </div>
</template>

<style scoped>
.history-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 0 12px;
}

.group-label {
  font-size: 12px;
  color: var(--fg-muted);
  padding: 0 10px 4px;
  font-weight: 500;
}

.group-items {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.history-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
  color: var(--fg-secondary);
  font-size: 14px;
  transition: background-color var(--dur-fast) var(--ease-out), color var(--dur-fast) var(--ease-out);
}

.history-item:hover,
.history-item:focus-visible {
  background-color: var(--bg-panel);
  color: var(--fg-primary);
}

.history-item.active {
  background-color: var(--accent-soft);
  color: var(--fg-primary);
  font-weight: 500;
}

.item-icon {
  flex-shrink: 0;
  color: inherit;
}

/* hover 与键盘聚焦都要能触及操作按钮（不能只靠 hover） */
.item-actions {
  opacity: 0;
  transition: opacity var(--dur-fast) var(--ease-out);
}
.history-item:hover .item-actions,
.history-item:focus-within .item-actions {
  opacity: 1;
}

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 4px;
  color: var(--fg-muted);
  background: transparent;
  border: none;
  cursor: pointer;
}

.action-btn:hover {
  background-color: var(--bg-sunken);
  color: var(--fg-primary);
}
</style>
