import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createAgentTurn } from '@/runtime/useAgentTurn'
import { createSseClient } from '@/transport/sse'
import type { Proposal } from '@/types/agent'

const mocks = vi.hoisted(() => ({ confirm: vi.fn(), reject: vi.fn(), stopTurn: vi.fn(), getHistory: vi.fn() }))

vi.mock('@/api/confirm', () => ({ confirm: mocks.confirm, reject: mocks.reject }))
vi.mock('@/api/messages', () => ({ stopTurn: mocks.stopTurn, getHistory: mocks.getHistory }))
vi.mock('@/transport/sse', () => ({ createSseClient: vi.fn() }))

describe('agent turn wire contract', () => {
  beforeEach(() => {
    Object.values(mocks).forEach((mock) => mock.mockReset())
    vi.mocked(createSseClient).mockReset()
    mocks.getHistory.mockResolvedValue({ records: [], total: 0, page: 1, size: 50 })
  })

  it('confirms an enabled proposal directly by proposal identity', async () => {
    mocks.confirm.mockResolvedValue({ proposalId: 'p1', taskId: 't1', status: 'CONFIRMED' })
    const turn = createAgentTurn({ conversationId: 'c1' })
    turn.__setPendingProposal(proposal({ enabled: true }))

    await expect(turn.confirm('p1')).resolves.toEqual({ taskId: 't1' })
    expect(mocks.confirm).toHaveBeenCalledWith('c1', 'p1')
  })

  it('queues a later message until the active turn completes', async () => {
    const clients: Array<Parameters<typeof createSseClient>[0]> = []
    vi.mocked(createSseClient).mockImplementation((options) => {
      clients.push(options)
      return { close: vi.fn() }
    })
    const turn = createAgentTurn({ conversationId: 'c1' })
    const first = turn.send('first')
    const second = turn.send('second')

    expect(turn.queuedCount.value).toBe(1)
    clients[0]?.onEvent({ name: 'completed', data: { messageId: 'm1', stopped: false } })
    await flushPromises()
    expect(clients).toHaveLength(2)

    clients[1]?.onEvent({ name: 'completed', data: { messageId: 'm2', stopped: false } })
    await expect(Promise.all([first, second])).resolves.toEqual([undefined, undefined])
  })

  it('keeps only safe product-language tool and transition facts', async () => {
    vi.mocked(createSseClient).mockImplementationOnce((options) => {
      queueMicrotask(() => {
        options.onEvent({ name: 'tool_status', data: { label: '读取素材', state: 'RUNNING' } })
        options.onEvent({ name: 'tool_status', data: { label: '读取素材', state: 'SUCCEEDED' } })
        options.onEvent({ name: 'transition', data: { label: '正在整理结果', state: 'RUNNING' } })
        options.onEvent({ name: 'completed', data: { messageId: null, stopped: false } })
      })
      return { close: vi.fn() }
    })
    const turn = createAgentTurn({ conversationId: 'c1' })

    await turn.send('inspect')

    expect(turn.timeline.value).toEqual([
      { id: 'tool-1', type: 'tool', label: '读取素材', status: 'SUCCEEDED' },
      { id: 'transition-2', type: 'transition', label: '正在整理结果', state: 'RUNNING' }
    ])
  })

  it('upserts proposal cards and enables them only after turn completion', async () => {
    vi.mocked(createSseClient).mockImplementationOnce((options) => {
      queueMicrotask(() => {
        options.onEvent({ name: 'proposal_ready', data: proposal() })
        options.onEvent({ name: 'completed', data: { messageId: 'm1', stopped: false } })
      })
      return { close: vi.fn() }
    })
    const turn = createAgentTurn({ conversationId: 'c1' })

    await turn.send('plan')

    expect(turn.proposals.value).toHaveLength(1)
    expect(turn.proposals.value[0]).toMatchObject({ proposalId: 'p1', enabled: true })
  })

  it('rejects the turn when the stream emits the safe error event', async () => {
    vi.mocked(createSseClient).mockImplementationOnce((options) => {
      queueMicrotask(() => options.onEvent({ name: 'error', data: { code: 'TURN_RUNNER_UNAVAILABLE', message: '暂时无法处理' } }))
      return { close: vi.fn() }
    })
    const turn = createAgentTurn({ conversationId: 'c1' })

    await expect(turn.send('hello')).rejects.toMatchObject({ errorCode: 'TURN_RUNNER_UNAVAILABLE' })
  })

  it('marks received text interrupted when the stream closes early', async () => {
    vi.mocked(createSseClient).mockImplementationOnce((options) => {
      queueMicrotask(() => {
        options.onEvent({ name: 'assistant_delta', data: { text: 'partial' } })
        options.onClose?.()
      })
      return { close: vi.fn() }
    })
    const turn = createAgentTurn({ conversationId: 'c1' })

    await expect(turn.send('hello')).rejects.toMatchObject({ errorCode: 'STREAM_INTERRUPTED' })
    expect(turn.timeline.value[0]).toMatchObject({ type: 'assistant', text: 'partial', status: 'interrupted' })
  })

  it('reconciles a durable assistant message when the stream closes before completed', async () => {
    mocks.getHistory.mockResolvedValue({
      records: [{
        messageId: 'message-9',
        seq: 9,
        role: 'ASSISTANT',
        content: 'durable answer',
        references: [],
        createdAt: '2026-07-21T00:00:00Z'
      }],
      total: 1,
      page: 1,
      size: 50
    })
    vi.mocked(createSseClient).mockImplementationOnce((options) => {
      queueMicrotask(() => {
        options.onEvent({ name: 'assistant_delta', data: { text: 'partial' } })
        options.onClose?.()
      })
      return { close: vi.fn() }
    })
    const turn = createAgentTurn({ conversationId: 'c1' })

    await expect(turn.send('hello')).rejects.toMatchObject({ errorCode: 'STREAM_INTERRUPTED' })
    expect(mocks.getHistory).toHaveBeenCalledWith('c1', { page: 1, size: 50 })
    expect(turn.timeline.value[0]).toMatchObject({
      type: 'assistant',
      messageId: 'message-9',
      text: 'durable answer',
      status: 'completed'
    })
  })

  it('pauses queued messages after failure and dispatches them only after Continue', async () => {
    const clients: Array<Parameters<typeof createSseClient>[0]> = []
    vi.mocked(createSseClient).mockImplementation((options) => {
      clients.push(options)
      return { close: vi.fn() }
    })
    const turn = createAgentTurn({ conversationId: 'c1' })
    const first = turn.send('first')
    const second = turn.send('second')

    clients[0]?.onEvent({ name: 'error', data: { code: 'TURN_FAILED', message: '处理失败' } })

    await expect(first).rejects.toMatchObject({ errorCode: 'TURN_FAILED' })
    expect(turn.queuePaused.value).toBe(true)
    expect(turn.queuedCount.value).toBe(1)
    expect(clients).toHaveLength(1)

    turn.continueQueue()
    expect(clients).toHaveLength(2)
    clients[1]?.onEvent({ name: 'completed', data: { messageId: 'm2', stopped: false } })
    await expect(second).resolves.toBeUndefined()
  })

  it('keeps an edited queue head in place and waits for Save before dispatch', async () => {
    const clients: Array<Parameters<typeof createSseClient>[0]> = []
    vi.mocked(createSseClient).mockImplementation((options) => {
      clients.push(options)
      return { close: vi.fn() }
    })
    const turn = createAgentTurn({ conversationId: 'c1' })
    const first = turn.send('first')
    const second = turn.send('second')
    const queuedId = turn.queuedMessages.value[0]!.id

    turn.beginQueuedEdit(queuedId)
    clients[0]?.onEvent({ name: 'completed', data: { messageId: 'm1', stopped: false } })
    await first
    expect(clients).toHaveLength(1)

    turn.saveQueuedEdit(queuedId, 'edited second', [{ referenceKey: 'package:7', displayPathSnapshot: 'summer.zip' }])
    expect(clients).toHaveLength(2)
    expect(clients[1]?.body).toEqual({
      prompt: 'edited second',
      references: [{ referenceKey: 'package:7', displayPathSnapshot: 'summer.zip' }]
    })
    clients[1]?.onEvent({ name: 'completed', data: { messageId: 'm2', stopped: false } })
    await second
  })

  it('keeps the stream open after Stop until the server sends stopped completion', async () => {
    let client: Parameters<typeof createSseClient>[0] | undefined
    const close = vi.fn()
    mocks.stopTurn.mockResolvedValue(undefined)
    vi.mocked(createSseClient).mockImplementationOnce((options) => {
      client = options
      return { close }
    })
    const turn = createAgentTurn({ conversationId: 'c1' })
    const pending = turn.send('stop me')

    turn.abort()
    await flushPromises()
    expect(mocks.stopTurn).toHaveBeenCalledWith('c1')
    expect(close).not.toHaveBeenCalled()

    client?.onEvent({ name: 'completed', data: { messageId: null, stopped: true } })
    await expect(pending).resolves.toBeUndefined()
    expect(turn.phase.value).toBe('cancelled')
  })
})

function proposal(extra: Partial<Proposal> = {}): Proposal {
  return {
    proposalId: 'p1',
    conversationId: 'c1',
    proposalType: 'IMAGE_PROCESS',
    title: '处理素材',
    summary: '统一尺寸',
    referenceSummaries: ['summer.zip / SKU-1'],
    createdAt: '2026-07-21T00:00:00Z',
    ...extra
  }
}

async function flushPromises(): Promise<void> {
  await Promise.resolve()
  await Promise.resolve()
}
