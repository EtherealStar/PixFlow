import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createAgentTurn } from '@/runtime/useAgentTurn'
import { createSseClient } from '@/transport/sse'
import type { Proposal } from '@/types/agent'

const mocks = vi.hoisted(() => ({
  confirm: vi.fn(),
  reject: vi.fn()
}))

vi.mock('@/api/confirm', () => ({
  confirm: mocks.confirm,
  reject: mocks.reject
}))

vi.mock('@/transport/sse', () => ({
  createSseClient: vi.fn()
}))

describe('agent turn HITL contract', () => {
  beforeEach(() => {
    sessionStorage.clear()
    mocks.confirm.mockReset()
    mocks.reject.mockReset()
    vi.mocked(createSseClient).mockReset()
  })

  it('confirms a proposal directly with an empty command body', async () => {
    const proposal: Proposal = {
      proposalId: 'p1',
      conversationId: 'c1',
      type: 'DAG'
    }
    mocks.confirm.mockResolvedValue({ proposalId: 'p1', taskId: 't1', status: 'CONFIRMED' })

    const turn = createAgentTurn({ conversationId: 'c1' })
    turn.__setPendingProposal(proposal)

    const result = await turn.confirm('p1')

    expect(result.taskId).toBe('t1')
    expect(mocks.confirm).toHaveBeenCalledOnce()
    expect(mocks.confirm).toHaveBeenCalledWith('c1', 'p1')
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

  it('queues a later message until the active SSE turn completes', async () => {
    const clients: Array<Parameters<typeof createSseClient>[0]> = []
    vi.mocked(createSseClient).mockImplementation((opts) => {
      clients.push(opts)
      return { close: vi.fn() }
    })

    const turn = createAgentTurn({ conversationId: 'c1' })

    const first = turn.send('first')
    const second = turn.send('second')

    expect(createSseClient).toHaveBeenCalledTimes(1)
    expect(turn.queuedCount.value).toBe(1)
    expect(clients[0]?.body).toMatchObject({ prompt: 'first' })

    clients[0]?.onEvent({
      name: 'completed',
      data: { finalText: 'first done' }
    })
    await flushPromises()

    expect(createSseClient).toHaveBeenCalledTimes(2)
    expect(turn.queuedCount.value).toBe(0)
    expect(clients[1]?.body).toMatchObject({ prompt: 'second' })

    clients[1]?.onEvent({
      name: 'completed',
      data: { finalText: 'second done' }
    })

    await expect(Promise.all([first, second])).resolves.toEqual([undefined, undefined])
  })

  it('reduces assistant deltas into a completed timeline item', async () => {
    vi.mocked(createSseClient).mockImplementationOnce((opts) => {
      queueMicrotask(() => {
        opts.onEvent({ name: 'assistant_delta', data: { text: '你', assistantCallId: 'a1', modelTurnIndex: 1 } })
        opts.onEvent({ name: 'assistant_delta', data: { text: '好', assistantCallId: 'a1', modelTurnIndex: 1 } })
        opts.onEvent({
          name: 'assistant_message_completed',
          data: { finalText: '你好', messageId: 'm1', assistantCallId: 'a1', modelTurnIndex: 1, traceId: 't1', turnNo: 1 }
        })
        opts.onEvent({ name: 'completed', data: { finalText: '你好', traceId: 't1', turnNo: 1 } })
      })
      return { close: vi.fn() }
    })

    const turn = createAgentTurn({ conversationId: 'c1' })
    await turn.send('hello')

    expect(turn.timeline.value).toHaveLength(1)
    expect(turn.timeline.value[0]).toMatchObject({
      type: 'assistant',
      assistantCallId: 'a1',
      modelTurnIndex: 1,
      text: '你好',
      status: 'completed',
      messageId: 'm1'
    })
    expect(turn.phase.value).toBe('completed')
  })

  it('renders tool lifecycle and the next assistant turn separately', async () => {
    vi.mocked(createSseClient).mockImplementationOnce((opts) => {
      queueMicrotask(() => {
        opts.onEvent({
          name: 'tool_call_ready',
          data: { toolName: 'search', toolCallId: 'tc1', toolInput: { q: 'sku' }, assistantCallId: 'a1', modelTurnIndex: 1 }
        })
        opts.onEvent({
          name: 'tool_started',
          data: { toolName: 'search', toolCallId: 'tc1', assistantCallId: 'a1', modelTurnIndex: 1 }
        })
        opts.onEvent({
          name: 'tool_result',
          data: { toolName: 'search', toolCallId: 'tc1', content: 'found 3 items', externalized: false, assistantCallId: 'a1', modelTurnIndex: 1 }
        })
        opts.onEvent({ name: 'transition', data: { reason: 'TOOL_USE', assistantCallId: 'a1', modelTurnIndex: 1 } })
        opts.onEvent({
          name: 'assistant_delta',
          data: { text: '已找到 3 个商品。', assistantCallId: 'a2', modelTurnIndex: 2 }
        })
        opts.onEvent({ name: 'completed', data: { finalText: '已找到 3 个商品。', traceId: 't1', turnNo: 1 } })
      })
      return { close: vi.fn() }
    })

    const turn = createAgentTurn({ conversationId: 'c1' })
    await turn.send('search')

    expect(turn.timeline.value).toHaveLength(2)
    expect(turn.timeline.value[0]).toMatchObject({
      type: 'tool',
      assistantCallId: 'a1',
      modelTurnIndex: 1,
      toolCallId: 'tc1',
      toolName: 'search',
      status: 'completed',
      result: 'found 3 items'
    })
    expect(turn.timeline.value[1]).toMatchObject({
      type: 'assistant',
      assistantCallId: 'a2',
      modelTurnIndex: 2,
      text: '已找到 3 个商品。',
      status: 'completed'
    })
  })

  it('keeps timeline text when the stream closes before completed', async () => {
    vi.mocked(createSseClient).mockImplementationOnce((opts) => {
      queueMicrotask(() => {
        opts.onEvent({ name: 'assistant_delta', data: { text: 'partial', assistantCallId: 'a1', modelTurnIndex: 1 } })
        opts.onClose?.()
      })
      return { close: vi.fn() }
    })

    const turn = createAgentTurn({ conversationId: 'c1' })

    await expect(turn.send('hello')).rejects.toMatchObject({
      errorCode: 'STREAM_INTERRUPTED'
    })
    expect(turn.timeline.value[0]).toMatchObject({
      type: 'assistant',
      text: 'partial',
      status: 'streaming'
    })
    expect(turn.phase.value).toBe('error')
  })

  it('keeps streaming when a rate limit retry transition arrives', async () => {
    const clients: Array<Parameters<typeof createSseClient>[0]> = []
    vi.mocked(createSseClient).mockImplementationOnce((opts) => {
      clients.push(opts)
      return { close: vi.fn() }
    })

    const turn = createAgentTurn({ conversationId: 'c1' })
    const promise = turn.send('hello')

    clients[0]?.onEvent({
      name: 'transition',
      data: {
        reason: 'RATE_LIMIT_RETRY',
        attempt: 2,
        retriesRemaining: 9,
        errorCode: 'MODEL_PROVIDER_ERROR',
        message: 'model stream interrupted',
        assistantCallId: 'a1',
        modelTurnIndex: 1
      }
    })
    await flushPromises()

    expect(turn.phase.value).toBe('streaming')
    expect(turn.timeline.value[0]).toMatchObject({
      type: 'transition',
      reason: 'RATE_LIMIT_RETRY',
      attempt: 2,
      retriesRemaining: 9,
      errorCode: 'MODEL_PROVIDER_ERROR',
      retrying: true
    })

    clients[0]?.onEvent({ name: 'completed', data: { finalText: 'done' } })
    await expect(promise).resolves.toBeUndefined()
  })

  it('preserves retry transition when the retried assistant completes', async () => {
    vi.mocked(createSseClient).mockImplementationOnce((opts) => {
      queueMicrotask(() => {
        opts.onEvent({
          name: 'transition',
          data: { reason: 'RATE_LIMIT_RETRY', attempt: 2, retriesRemaining: 9, assistantCallId: 'a1', modelTurnIndex: 1 }
        })
        opts.onEvent({ name: 'assistant_delta', data: { text: '重试后继续', assistantCallId: 'a1', modelTurnIndex: 1 } })
        opts.onEvent({ name: 'completed', data: { finalText: '重试后继续', traceId: 't1', turnNo: 1 } })
      })
      return { close: vi.fn() }
    })

    const turn = createAgentTurn({ conversationId: 'c1' })
    await turn.send('hello')

    expect(turn.phase.value).toBe('completed')
    expect(turn.timeline.value).toHaveLength(2)
    expect(turn.timeline.value[0]).toMatchObject({
      type: 'transition',
      reason: 'RATE_LIMIT_RETRY',
      attempt: 2
    })
    expect(turn.timeline.value[1]).toMatchObject({
      type: 'assistant',
      assistantCallId: 'a1',
      text: '重试后继续',
      status: 'completed'
    })
  })
})

async function flushPromises(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
}
