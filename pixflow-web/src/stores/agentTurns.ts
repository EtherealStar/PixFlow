import { defineStore } from 'pinia'
import { markRaw, ref, shallowReactive } from 'vue'
import type { AgentTurnSummary } from '@/types/agent'
import { createAgentTurn, type AgentTurn } from '@/runtime/useAgentTurn'

/**
 * 仅存回合摘要状态，不保存 timeline 或流式 payload。
 * timeline 维护在 useAgentTurn composable 内。
 */
export const useAgentTurnsStore = defineStore('agentTurns', () => {
  const turns = ref<Map<string, AgentTurnSummary>>(new Map())
  const runtimes = shallowReactive(new Map<string, AgentTurn>())
  const lastTraceId = ref<string | null>(null)

  function set(conversationId: string, summary: AgentTurnSummary): void {
    turns.value.set(conversationId, summary)
    if (summary.error?.traceId) lastTraceId.value = summary.error.traceId
  }

  function get(conversationId: string): AgentTurnSummary | undefined {
    return turns.value.get(conversationId)
  }

  function reset(conversationId: string): void {
    runtimes.get(conversationId)?.dispose()
    runtimes.delete(conversationId)
    turns.value.delete(conversationId)
  }

  function getOrCreateRuntime(conversationId: string): AgentTurn {
    const existing = runtimes.get(conversationId)
    if (existing) return existing
    const runtime = markRaw(createAgentTurn({ conversationId }))
    runtimes.set(conversationId, runtime)
    return runtime
  }

  function clearAll(): void {
    for (const runtime of runtimes.values()) runtime.dispose()
    runtimes.clear()
    turns.value.clear()
    lastTraceId.value = null
  }

  return { turns, runtimes, lastTraceId, set, get, reset, getOrCreateRuntime, clearAll }
})
