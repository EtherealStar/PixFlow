import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createAgentTurn } from '@/runtime/useAgentTurn'
import { createSseClient } from '@/transport/sse'
import type { Proposal } from '@/types/agent'

const mocks = vi.hoisted(() => ({
  challenge: vi.fn(),
  submit: vi.fn()
}))

vi.mock('@/api/confirm', () => ({
  challenge: mocks.challenge,
  submit: mocks.submit
}))

vi.mock('@/transport/sse', () => ({
  createSseClient: vi.fn()
}))

describe('agent turn HITL contract', () => {
  beforeEach(() => {
    sessionStorage.clear()
    mocks.challenge.mockReset()
    mocks.submit.mockReset()
  })

  it('calls challenge once, then submits answer with challengeId', async () => {
    const proposal: Proposal = {
      proposalId: 'p1',
      conversationId: 'c1',
      type: 'DAG'
    }
    mocks.challenge.mockResolvedValue({
      needChallenge: true,
      challenge: { challengeId: 'ch1', prompt: '请输入确认' }
    })
    mocks.submit.mockResolvedValue({ proposalId: 'p1', taskId: 't1', status: 'CONFIRMED' })

    const turn = createAgentTurn({ conversationId: 'c1' })
    turn.__setPendingProposal(proposal)

    const first = await turn.confirm('p1')
    const second = await turn.confirm('p1', '确认')

    expect(first.challenge?.challengeId).toBe('ch1')
    expect(second.taskId).toBe('t1')
    expect(mocks.challenge).toHaveBeenCalledTimes(1)
    expect(mocks.challenge).toHaveBeenCalledWith('c1', 'p1')
    expect(mocks.submit.mock.calls[0]?.[2]).toEqual({ challengeId: 'ch1', challengeAnswer: '确认' })
  })

  it('rejects send when the SSE stream emits an error event', async () => {
    vi.mocked(createSseClient).mockImplementationOnce((opts) => {
      queueMicrotask(() => {
        opts.onEvent({
          name: 'error',
          data: { message: 'agent turn runner is not configured', errorCode: 'TURN_RUNNER_UNAVAILABLE' }
        })
      })
      return { close: vi.fn() }
    })

    const turn = createAgentTurn({ conversationId: 'c1' })

    await expect(turn.send('hello')).rejects.toMatchObject({
      errorCode: 'TURN_RUNNER_UNAVAILABLE',
      message: 'agent turn runner is not configured'
    })
  })
})
