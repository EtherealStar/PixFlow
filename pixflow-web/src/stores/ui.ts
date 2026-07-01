import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { useAgentTurnsStore } from './agentTurns'

/**
 * UI 状态：sidebar / floatingTraceId。
 * floatingTraceId 默认取最近一次回合的 traceId（监听 agentTurnsStore 变化）。
 */
export const useUiStore = defineStore('ui', () => {
  const sidebarOpen = ref(true)
  const floatingTraceId = ref<string | null>(null)

  const agentTurns = useAgentTurnsStore()
  watch(() => agentTurns.lastTraceId, (v) => {
    if (v) floatingTraceId.value = v
  })

  function toggleSidebar(): void { sidebarOpen.value = !sidebarOpen.value }

  return { sidebarOpen, floatingTraceId, toggleSidebar }
})