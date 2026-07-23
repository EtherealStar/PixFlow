import type { ApiError } from './api'

export type AgentTurnPhase = 'idle' | 'sending' | 'streaming' | 'completed' | 'error' | 'cancelled'

export interface Proposal {
  proposalId: string
  conversationId: string
  proposalType: 'IMAGE_PROCESS' | 'IMAGEGEN'
  title: string
  summary: string
  referenceSummaries: string[]
  createdAt: string
  enabled?: boolean
  rejected?: boolean
  confirmedTaskId?: string
}

export interface AgentTurnSummary {
  conversationId: string
  phase: AgentTurnPhase
  error?: ApiError
  queuedCount?: number
}

export type AgentEventName =
  | 'assistant_delta'
  | 'assistant_message_completed'
  | 'tool_status'
  | 'transition'
  | 'proposal_ready'
  | 'completed'
  | 'error'

export type ToolStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'

export type TimelineItem = AssistantTimelineItem | ToolTimelineItem | TransitionTimelineItem | ErrorTimelineItem

export interface AssistantTimelineItem {
  id: string
  type: 'assistant'
  text: string
  status: 'streaming' | 'completed' | 'interrupted'
  messageId?: string
}

export interface ToolTimelineItem {
  id: string
  type: 'tool'
  label: string
  status: ToolStatus
}

export interface TransitionTimelineItem {
  id: string
  type: 'transition'
  label: string
  state?: string
}

export interface ErrorTimelineItem {
  id: string
  type: 'error'
  message: string
  errorCode?: string
  traceId?: string
}
