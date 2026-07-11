import type { ApiError } from '@/types/api'
import { refreshAuthSessionOnce } from '@/transport/authRefresh'
import { getAccessToken } from '@/transport/authToken'
import { newTraceId } from '@/utils/id'
import { normalizeHttpError, readHttpError } from '@/transport/httpError'

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
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
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
  /** 是否自动注入 access token；登录、注册、刷新入口必须关闭，避免残留坏 token 污染鉴权入口。 */
  auth?: boolean
  /** 是否允许 access token 过期后刷新并重试一次；默认跟随 auth。 */
  authRefresh?: boolean
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

  const buildHeaders = (): Record<string, string> => {
    const headers: Record<string, string> = {
      Accept: 'application/json',
      [TRACE_HEADER]: traceId,
      ...(opts.headers ?? {})
    }
    const token = opts.auth === false ? null : getAccessToken()
    if (token && !headers.Authorization) {
      headers.Authorization = `Bearer ${token}`
    }
    if (opts.idempotencyKey) headers[IDEMPOTENCY_HEADER] = opts.idempotencyKey
    return headers
  }

  let body: BodyInit | undefined
  if (opts.body !== undefined && method !== 'GET') {
    body = toRequestBody(opts.body)
  }

  const doFetch = async (allowAuthRefresh = true): Promise<T> => {
    if (!opts.skipInFlight) await inFlight.acquire()
    try {
      const headers = buildHeaders()
      if (body !== undefined && !isRawBody(opts.body) && !headers['Content-Type']) {
        headers['Content-Type'] = typeof opts.body === 'string' ? 'text/plain' : 'application/json'
      }
      const res = await withTimeout(
        fetch(path, { method, headers, body, signal: opts.signal, credentials: 'same-origin' }),
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
        const err = await readHttpError(res, responseTraceId)
        if (retryAfterMs !== undefined) err.retryAfterMs = retryAfterMs
        if (await shouldRefreshAndRetry(err, opts, allowAuthRefresh)) {
          return await doFetch(false)
        }
        throw err
      }
      if (res.status === 204) return undefined as T
      const ct = res.headers.get('content-type') ?? ''
      if (ct.includes('application/json')) {
        const json = await res.json()
        if (json && typeof json === 'object' && 'success' in json && 'data' in json) {
          const envelope = json as { success?: boolean; data?: unknown; code?: string; message?: string; traceId?: string }
          if (envelope.success === false) {
            throw normalizeHttpError(res.status, envelope, responseTraceId)
          }
          return normalizePayload(envelope.data) as T
        }
        return normalizePayload(json) as T
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
        return await doFetch(false)
      }
      throw err
    }
  }
  return await doFetch()
}

function toRequestBody(value: BodyInit | unknown): BodyInit {
  if (isRawBody(value)) return value
  return JSON.stringify(value)
}

function isRawBody(value: unknown): value is BodyInit {
  return (
    typeof value === 'string' ||
    value instanceof FormData ||
    value instanceof Blob ||
    value instanceof ArrayBuffer ||
    value instanceof URLSearchParams ||
    ArrayBuffer.isView(value)
  )
}

async function shouldRefreshAndRetry(err: ApiError, opts: RequestOptions, allowAuthRefresh: boolean): Promise<boolean> {
  if (!allowAuthRefresh) return false
  if (opts.auth === false || opts.authRefresh === false) return false
  if (err.status !== 401 || err.errorCode !== 'AUTH_TOKEN_EXPIRED') return false
  return await refreshAuthSessionOnce()
}

function normalizePayload(value: unknown): unknown {
  if (value && typeof value === 'object') {
    const obj = value as Record<string, unknown>
    if (Array.isArray(obj.records) && obj.items === undefined) {
      return { ...obj, items: obj.records }
    }
  }
  return value
}
