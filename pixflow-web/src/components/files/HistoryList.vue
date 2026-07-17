<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useConversationsStore } from '@/stores/conversations'
import { useToastStore } from '@/stores/toast'
import IconChat from '@/components/icons/IconChat.vue'
import IconMoreHorizontal from '@/components/icons/IconMoreHorizontal.vue'
import AppDropdownMenu from '@/components/ui/AppDropdownMenu.vue'
import AppDropdownMenuItem from '@/components/ui/AppDropdownMenuItem.vue'

/**
 * HistoryList — 侧边栏扁平化历史会话列表
 */
const conversations = useConversationsStore()
const router = useRouter()
const toast = useToastStore()

onMounted(() => {
  // 确保有数据
  if (conversations.items.length === 0) {
    void conversations.refresh().catch(showError)
  }
})

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

async function onRemove(id: string, title?: string): Promise<void> {
  const ok = window.confirm(`确认删除会话 ${title || id} 吗？`)
  if (!ok) return
  await conversations.archive(id)
  toast.push({ variant: 'info', message: '已删除' })
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
          :class="{ active: $route.params.cid === item.conversationId }"
          @click="onSelect(item.conversationId)"
        >
          <IconChat
            :size="16"
            class="item-icon"
          />
          <span class="item-title flex-1 truncate">{{ item.title || '新会话' }}</span>
          
          <!-- Hover 操作 -->
          <div class="item-actions opacity-0 group-hover/item:opacity-100 transition-opacity">
            <AppDropdownMenu>
              <template #trigger>
                <button
                  class="action-btn"
                  aria-label="更多操作"
                  @click.stop
                >
                  <IconMoreHorizontal :size="16" />
                </button>
              </template>
              <AppDropdownMenuItem
                danger
                @select="onRemove(item.conversationId, item.title)"
              >
                删除
              </AppDropdownMenuItem>
            </AppDropdownMenu>
          </div>
        </li>
      </ul>
    </div>
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
  padding: 0 8px 4px;
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
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
  color: var(--fg-secondary);
  font-size: 14px;
  transition: background-color 0.15s, color 0.15s;
}

.history-item:hover {
  background-color: var(--bg-sunken);
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

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 2px;
  border-radius: 4px;
  color: var(--fg-muted);
  background: transparent;
  border: none;
  cursor: pointer;
}

.action-btn:hover {
  background-color: var(--border);
  color: var(--fg-primary);
}
</style>
