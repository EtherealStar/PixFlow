import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as api from '@/api/conversations'
import type { ConversationDetail, ConversationSummary } from '@/api/conversations'

export const useConversationsStore = defineStore('conversations', () => {
  const items = ref<ConversationSummary[]>([])
  const currentId = ref<string | null>(null)
  const current = ref<ConversationDetail | null>(null)
  const loading = ref(false)

  async function refresh(): Promise<void> {
    loading.value = true
    try {
      const page = await api.listConversations({ includeArchived: false, page: 1, size: 50 })
      items.value = page.items
    } finally {
      loading.value = false
    }
  }

  async function select(id: string): Promise<void> {
    currentId.value = id
    current.value = await api.getConversation(id)
  }

  async function create(payload?: { title?: string }): Promise<ConversationDetail> {
    const c = await api.createConversation(payload)
    currentId.value = c.conversationId
    current.value = c
    return c
  }

  async function archive(id: string): Promise<void> {
    await api.archiveConversation(id)
    items.value = items.value.filter((c) => c.conversationId !== id)
  }

  return { items, currentId, current, loading, refresh, select, create, archive }
})
