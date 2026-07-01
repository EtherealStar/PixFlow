import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { AgentTurnSummary } from '@/types/agent'

/**
 * 仅存"回合摘要状态"（phase / proposal / taskId），**不**存 deltas。
 * deltas 维护在 useAgentTurn composable 内的 ref<string>。
 */
export const useAgentTurnsStore = defineStore('agentTurns', () => {
  const turns = ref<Map<string, AgentTurnSummary>>(new Map())
  const lastTraceId = ref<string | null>(null)

  function set(conversationId: string, summary: AgentTurnSummary): void {
    turns.value.set(conversationId, summary)
    if (summary.traceId) lastTraceId.value = summary.traceId
  }

  function get(conversationId: string): AgentTurnSummary | undefined {
    return turns.value.get(conversationId)
  }

  function reset(conversationId: string): void {
    turns.value.delete(conversationId)
  }

  return { turns, lastTraceId, set, get, reset }
})