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
}

/** /challenge 响应。 */
export interface ChallengeOrToken {
  needChallenge: boolean
  challenge?: ConfirmationChallenge
  token?: string | ConfirmationToken
}

/** Agent 回合摘要状态（Pinia 持久化部分）。运行中的 timeline 留在 composable 内，不进入 Pinia。 */
export interface AgentTurnSummary {
  conversationId: string
  phase: AgentTurnPhase
  proposal?: Proposal
  taskId?: string
  error?: ApiError
  traceId?: string
  queuedCount?: number
}

/** SSE 事件类型。 */
export type AgentEventName =
  | 'assistant_delta'
  | 'assistant_message_completed'
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

export interface AgentEventAttribution {
  assistantCallId?: string
  modelTurnIndex?: number
  iteration?: number
  traceId?: string
  turnNo?: number
}

export interface AssistantDeltaPayload extends AgentEventAttribution { text: string }
export interface AssistantMessageCompletedPayload extends AgentEventAttribution { finalText: string; messageId?: string }
export interface ToolCallReadyPayload extends AgentEventAttribution { toolName: string; toolCallId: string; toolInput?: unknown }
export interface ToolStartedPayload extends AgentEventAttribution { toolCallId: string; toolName?: string }
export interface ToolResultPayload extends AgentEventAttribution {
  toolCallId: string
  toolName?: string
  content: string
  metadata?: Record<string, unknown>
  externalized: boolean
  error?: boolean
}
export interface TransitionPayload extends AgentEventAttribution {
  reason: string
  attempt?: number
  retriesRemaining?: number
  errorCode?: string
  message?: string
  retrying?: boolean
}
export interface CompletedPayload extends AgentEventAttribution { finalText: string }
export interface ErrorEventPayload { errorCode?: string; message: string; traceId?: string }

export type TimelineItem =
  | AssistantTimelineItem
  | ToolTimelineItem
  | TransitionTimelineItem
  | ErrorTimelineItem

export interface AssistantTimelineItem {
  id: string
  type: 'assistant'
  assistantCallId: string
  modelTurnIndex: number
  text: string
  status: 'streaming' | 'completed'
  messageId?: string
  traceId?: string
  turnNo?: number
}

export interface ToolTimelineItem {
  id: string
  type: 'tool'
  assistantCallId: string
  modelTurnIndex: number
  toolCallId: string
  toolName: string
  input?: unknown
  result?: string
  metadata?: Record<string, unknown>
  status: 'queued' | 'running' | 'completed' | 'error'
  externalized?: boolean
  traceId?: string
  turnNo?: number
}

export interface TransitionTimelineItem {
  id: string
  type: 'transition'
  reason: string
  assistantCallId?: string
  modelTurnIndex?: number
  attempt?: number
  retriesRemaining?: number
  errorCode?: string
  message?: string
  retrying?: boolean
  traceId?: string
  turnNo?: number
}

export interface ErrorTimelineItem {
  id: string
  type: 'error'
  message: string
  errorCode?: string
  traceId?: string
}
