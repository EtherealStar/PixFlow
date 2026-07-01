import type { ApiError } from './api'

/** Agent 回合状态机阶段。 */
export type AgentTurnPhase =
  | 'idle'
  | 'sending'
  | 'streaming'
  | 'awaiting_challenge'
  | 'awaiting_confirm'
  | 'completed'
  | 'error'
  | 'cancelled'

/** 待确认提案（来自后端 DAG / IMAGEGEN 提案的抽象）。 */
export interface Proposal {
  proposalId: string
  conversationId: string
  type: 'DAG' | 'IMAGEGEN'
  expectedCount?: number | null
  summary?: string
  payload?: Record<string, unknown>
}

/** 二次确认 challenge（needChallenge=true 时由后端返回）。 */
export interface ConfirmationChallenge {
  challengeId: string
  prompt: string
}

/** 二次确认 token（needChallenge=false 或答对 challenge 后由后端返回）。 */
export interface ConfirmationToken {
  token: string
  expiresAt: string
}

/** /challenge 响应。 */
export interface ChallengeOrToken {
  needChallenge: boolean
  challenge?: ConfirmationChallenge
  token?: ConfirmationToken
}

/** Agent 回合摘要状态（Pinia 持久化部分）。deltas 不入 state，留作 composable 内的 ref。 */
export interface AgentTurnSummary {
  conversationId: string
  phase: AgentTurnPhase
  proposal?: Proposal
  taskId?: string
  error?: ApiError
  traceId?: string
}

/** SSE 事件类型。 */
export type AgentEventName =
  | 'assistant_delta'
  | 'tool_call_ready'
  | 'tool_started'
  | 'tool_result'
  | 'transition'
  | 'completed'
  | 'error'

export interface AgentEvent<T = unknown> {
  name: AgentEventName
  data: T
  traceId?: string
}

export interface AssistantDeltaPayload { text: string }
export interface ToolCallReadyPayload { toolName: string; toolCallId: string; toolInput: Record<string, unknown> }
export interface ToolStartedPayload { toolCallId: string }
export interface ToolResultPayload { toolCallId: string; content: string; externalized: boolean }
export interface TransitionPayload { reason: string }
export interface CompletedPayload { finalText: string; traceId: string; turnNo: number }
export interface ErrorEventPayload { errorCode: string; message: string; traceId: string }