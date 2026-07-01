import type { ApiError } from '@/types/api'
import { newTraceId } from '@/utils/id'

/**
 * 统一 HTTP 客户端（fetch 封装）。
 * 见 web.md §5.1。
 *
 * 职责：
 *  - 注入 X-Trace-Id（生成或透传）
 *  - 错误归一化为 ApiError（按 `base/common.md` envelope）
 *  - GET 网络错 / 5xx 自动重试 1 次（指数退避 500ms）
 *  - 暴露 inFlightSignal 全局并发保护（见 §六）
 */
export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: BodyInit | unknown
  headers?: Record<string, string>
  /** 调用方传入的 Idempotency-Key；不传则 client 不自动生成 */
  idempotencyKey?: string
  /** 调用方传入的 traceId；不传则 client 生成 */
  traceId?: string
  /** 强制 multipart（body 为 FormData 时由 fetch 推断） */
  multipart?: boolean
  /** AbortSignal 透传 */
  signal?: AbortSignal
  /** 不走 GET 自动重试逻辑（默认 GET 自动重试 1 次） */
  noRetry?: boolean
  /** 跳过 inFlight 信号量（用于 SSE / 自身管控） */
  skipInFlight?: boolean
  /** 自定义超时（ms，默认 30000） */
  timeoutMs?: number
}

const DEFAULT_TIMEOUT = 30_000

const TRACE_HEADER = 'X-Trace-Id'
const IDEMPOTENCY_HEADER = 'Idempotency-Key'

// ---- inFlight 信号量（§六 全局并发保护） ----
class InFlightSemaphore {
  private inflight = 0
  private waiters: Array<() => void> = []

  constructor(private readonly capacity: number) {}

  async acquire(): Promise<void> {
    if (this.inflight < this.capacity) {
      this.inflight++
      return
    }
    return new Promise<void>((resolve) => this.waiters.push(resolve))
  }

  release(): void {
    const next = this.waiters.shift()
    if (next) {
      next()
    } else {
      this.inflight = Math.max(0, this.inflight - 1)
    }
  }
}

const inFlight = new InFlightSemaphore(6)

export function configureInFlightCapacity(capacity: number): void {
  ;(inFlight as unknown as { capacity: number }).capacity = capacity
}

// ---- 错误归一化 ----
function normalizeError(status: number, body: unknown, fallbackTraceId: string): ApiError {
  const obj = (body && typeof body === 'object' ? body : {}) as Record<string, unknown>
  const errorCode = String(obj.errorCode ?? obj.code ?? 'INTERNAL_ERROR')
  const message = String(obj.message ?? `HTTP ${status}`)
  const traceId = String(obj.traceId ?? fallbackTraceId)
  const details = (obj.details && typeof obj.details === 'object' ? obj.details : undefined) as
    | Record<string, unknown>
    | undefined
  const out: ApiError = { status, errorCode, message, traceId }
  if (details) out.details = details
  return out
}

function makeNetworkError(err: unknown, traceId: string): ApiError {
  return {
    status: 0,
    errorCode: 'NETWORK_ERROR',
    message: err instanceof Error ? err.message : 'network error',
    traceId
  }
}

async function withTimeout<T>(p: Promise<T>, ms: number, signal?: AbortSignal): Promise<T> {
  if (signal) {
    return p
  }
  return new Promise<T>((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`request timeout after ${ms}ms`)), ms)
    p.then(
      (v) => {
        clearTimeout(t)
        resolve(v)
      },
      (e) => {
        clearTimeout(t)
        reject(e)
      }
    )
  })
}

// ---- 主入口 ----
export async function request<T = unknown>(path: string, opts: RequestOptions = {}): Promise<T> {
  const method = opts.method ?? 'GET'
  const traceId = opts.traceId ?? newTraceId()

  const headers: Record<string, string> = {
    Accept: 'application/json',
    [TRACE_HEADER]: traceId,
    ...(opts.headers ?? {})
  }
  if (opts.idempotencyKey) headers[IDEMPOTENCY_HEADER] = opts.idempotencyKey

  let body: BodyInit | undefined
  if (opts.body !== undefined && method !== 'GET') {
    if (opts.multipart && opts.body instanceof FormData) {
      body = opts.body
    } else if (typeof opts.body === 'string') {
      body = opts.body
      if (!headers['Content-Type']) headers['Content-Type'] = 'text/plain'
    } else {
      body = JSON.stringify(opts.body)
      if (!headers['Content-Type']) headers['Content-Type'] = 'application/json'
    }
  }

  const doFetch = async (): Promise<T> => {
    if (!opts.skipInFlight) await inFlight.acquire()
    try {
      const res = await withTimeout(
        fetch(path, { method, headers, body, signal: opts.signal }),
        opts.timeoutMs ?? DEFAULT_TIMEOUT,
        opts.signal
      )
      const responseTraceId = res.headers.get(TRACE_HEADER) ?? traceId
      if (!res.ok) {
        // 处理 429 Retry-After
        let retryAfterMs: number | undefined
        if (res.status === 429) {
          const ra = res.headers.get('Retry-After')
          if (ra) {
            const n = Number(ra)
            if (!Number.isNaN(n)) retryAfterMs = n * 1000
          }
        }
        let bodyText = ''
        try {
          bodyText = await res.text()
        } catch {
          // ignore
        }
        let bodyJson: unknown = bodyText
        try {
          if (bodyText) bodyJson = JSON.parse(bodyText)
        } catch {
          // 非 JSON
        }
        const err = normalizeError(res.status, bodyJson, responseTraceId)
        if (retryAfterMs !== undefined) err.retryAfterMs = retryAfterMs
        throw err
      }
      if (res.status === 204) return undefined as T
      const ct = res.headers.get('content-type') ?? ''
      if (ct.includes('application/json')) {
        return (await res.json()) as T
      }
      return (await res.text()) as unknown as T
    } catch (e: unknown) {
      if (e && typeof e === 'object' && 'errorCode' in e && 'status' in e) {
        throw e as ApiError
      }
      throw makeNetworkError(e, traceId)
    } finally {
      if (!opts.skipInFlight) inFlight.release()
    }
  }

  if (method === 'GET' && !opts.noRetry) {
    try {
      return await doFetch()
    } catch (e: unknown) {
      const err = e as ApiError
      if (err.status === 0 || (err.status >= 500 && err.status < 600)) {
        // 退避 500ms 后重试一次
        await new Promise((r) => setTimeout(r, 500))
        return await doFetch()
      }
      throw err
    }
  }
  return await doFetch()
}
