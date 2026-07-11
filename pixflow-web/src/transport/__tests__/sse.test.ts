import { afterEach, describe, expect, it, vi } from 'vitest'
import { createSseClient } from '@/transport/sse'

const flush = async (): Promise<void> => {
  await Promise.resolve()
  await Promise.resolve()
}

describe('createSseClient HTTP errors', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it.each([
    [503, 'TURN_CAPACITY_EXCEEDED'],
    [404, 'CONVERSATION_NOT_FOUND']
  ])('preserves backend envelope for %s', async (status, code) => {
    const onError = vi.fn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ success: false, code, message: 'backend message', traceId: 'server-trace' }),
      { status, headers: { 'Content-Type': 'application/json' } }
    )))

    createSseClient({ url: '/stream', onEvent: vi.fn(), onError })
    await flush()

    expect(onError).toHaveBeenCalledWith(expect.objectContaining({
      status,
      errorCode: code,
      message: 'backend message',
      traceId: 'server-trace'
    }))
  })

  it('falls back to HTTP status for a non-JSON proxy response', async () => {
    const onError = vi.fn()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Bad Gateway', { status: 502 })))

    createSseClient({ url: '/stream', onEvent: vi.fn(), onError })
    await flush()

    expect(onError).toHaveBeenCalledWith(expect.objectContaining({
      status: 502,
      errorCode: 'HTTP_502',
      message: 'Bad Gateway'
    }))
  })

  it('does not duplicate terminal callbacks when caller aborts', async () => {
    const controller = new AbortController()
    const onError = vi.fn()
    const onClose = vi.fn()
    vi.stubGlobal('fetch', vi.fn((_url: string, init: RequestInit) => new Promise((_resolve, reject) => {
      init.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    })))

    createSseClient({
      url: '/stream',
      signal: controller.signal,
      onEvent: vi.fn(),
      onError,
      onClose
    })
    controller.abort()
    await flush()

    expect(onError).not.toHaveBeenCalled()
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
