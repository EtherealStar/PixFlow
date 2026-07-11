import { z } from 'zod'
import type { ApiError } from '@/types/api'
import { getAccessToken } from '@/transport/authToken'
import { newTraceId } from '@/utils/id'
import { readHttpError } from '@/transport/httpError'

/**
 * SSE 通用客户端（fetch + ReadableStream）。
 * 见 web.md §5.2。
 *
 *  - 不使用 EventSource（无法注入自定义 header）
 *  - 注释行（`:` 开头）识别 heartbeat
 *  - 30s 无数据视为真断
 *  - 每个事件按 name 派发；Zod 校验失败触发 onError + trace breadcrumb
 *  - V1 不做 SSE 续传（Last-Event-ID 后端不支持），断流即视为回合失败
 */
export interface SseClientOptions {
  url: string
  method?: 'GET' | 'POST'
  body?: unknown
  headers?: Record<string, string>
  signal?: AbortSignal
  heartbeatMs?: number
  onEvent: (event: { name: string; data: unknown; traceId?: string }) => void
  onError: (error: ApiError) => void
  onOpen?: () => void
  onClose?: () => void
}

const DEFAULT_HEARTBEAT = 30_000

function parseEventBlock(block: string): { name?: string; data?: string } {
  const lines = block.split(/\r?\n/)
  const out: { name?: string; data?: string[] } = {}
  for (const line of lines) {
    if (!line) continue
    if (line.startsWith(':')) continue // heartbeat / comment
    const idx = line.indexOf(':')
    if (idx === -1) {
      if (line.startsWith('event:')) out.name = line.slice(6).trim()
      else if (line.startsWith('data:')) (out.data ??= []).push(line.slice(5).trim())
    } else {
      const field = line.slice(0, idx)
      const value = line.slice(idx + 1).replace(/^ /, '')
      if (field === 'event') out.name = value
      else if (field === 'data') (out.data ??= []).push(value)
    }
  }
  return { name: out.name, data: out.data?.join('\n') }
}

const eventEnvelope = z.object({
  // 透传：具体事件 schema 由调用方校验
}).passthrough()

export function createSseClient(opts: SseClientOptions): { close: () => void } {
  const heartbeat = opts.heartbeatMs ?? DEFAULT_HEARTBEAT
  const traceId = newTraceId()
  let closed = false
  let lastDataAt = Date.now()
  const heartbeatTimer = window.setInterval(() => {
    if (Date.now() - lastDataAt > heartbeat) {
      // 真断
      if (!closed) {
        closed = true
        const err: ApiError = {
          status: 0,
          errorCode: 'STREAM_INTERRUPTED',
          message: `SSE stream silent for ${heartbeat}ms`,
          traceId
        }
        opts.onError(err)
        try { controller?.abort() } catch { /* ignore */ }
      }
    }
  }, heartbeat / 2)

  const headers: Record<string, string> = {
    Accept: 'text/event-stream',
    'X-Trace-Id': traceId,
    ...(opts.headers ?? {})
  }
  const token = getAccessToken()
  if (token && !headers.Authorization) {
    headers.Authorization = `Bearer ${token}`
  }
  let body: BodyInit | undefined
  if (opts.body !== undefined) {
    body = JSON.stringify(opts.body)
    headers['Content-Type'] = 'application/json'
  }

  let controller: AbortController | undefined
  if (opts.signal) {
    opts.signal.addEventListener('abort', () => {
      if (!closed) {
        closed = true
        window.clearInterval(heartbeatTimer)
        opts.onClose?.()
      }
    })
  }

  void (async () => {
    controller = new AbortController()
    const signal = opts.signal ?? controller.signal
    try {
      const res = await fetch(opts.url, {
        method: opts.method ?? (opts.body ? 'POST' : 'GET'),
        headers,
        body,
        signal
      })
      if (!res.ok || !res.body) {
        const err = await readHttpError(res, traceId)
        closed = true
        window.clearInterval(heartbeatTimer)
        opts.onError(err)
        return
      }
      opts.onOpen?.()
      const reader = res.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      while (!closed) {
        const { value, done } = await reader.read()
        if (done) break
        lastDataAt = Date.now()
        buffer += decoder.decode(value, { stream: true })
        const blocks = buffer.split(/\r?\n\r?\n/)
        buffer = blocks.pop() ?? ''
        for (const block of blocks) {
          if (!block.trim()) continue
          const ev = parseEventBlock(block)
          if (!ev.name && !ev.data) continue
          let payload: unknown = ev.data
          if (ev.data) {
            try { payload = JSON.parse(ev.data) } catch { /* keep raw */ }
          }
          const result = eventEnvelope.safeParse(payload)
          if (!result.success) {
            // schema 校验失败 → 触发 onError + breadcrumb
            const err: ApiError = {
              status: 0,
              errorCode: 'SSE_PAYLOAD_INVALID',
              message: result.error.message,
              traceId
            }
            opts.onError(err)
            continue
          }
          try {
            opts.onEvent({ name: ev.name ?? 'message', data: payload, traceId })
          } catch (e) {
            // 业务层抛错不中断读取
            if (__DEV__) console.debug('[SSE] onEvent threw', e)
          }
        }
      }
    } catch (e: unknown) {
      if (closed) return
      const err: ApiError = {
        status: 0,
        errorCode: 'STREAM_INTERRUPTED',
        message: e instanceof Error ? e.message : 'SSE connection error',
        traceId
      }
      opts.onError(err)
    } finally {
      window.clearInterval(heartbeatTimer)
      if (!closed) opts.onClose?.()
      closed = true
    }
  })()

  return {
    close() {
      if (closed) return
      closed = true
      window.clearInterval(heartbeatTimer)
      try { controller?.abort() } catch { /* ignore */ }
      opts.onClose?.()
    }
  }
}
