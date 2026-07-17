import type { ApiError } from '@/types/api'

/**
 * dev 模式 console 守卫：统一脱敏认证类 token 字段。
 */
export function redactToken(value: string | undefined): string {
  if (!value) return ''
  if (value.length <= 6) return '***'
  return `${value.slice(0, 2)}***${value.slice(-2)}`
}

const TOKEN_KEY_RE = /(?:confirmationToken|token)/
const TOKEN_VALUES_RE = /^[A-Za-z0-9._-]{12,}$/

function redactDeep<T>(value: T): T {
  if (value === null || value === undefined) return value
  if (Array.isArray(value)) return value.map(redactDeep) as unknown as T
  if (typeof value === 'object') {
    const out: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      if (TOKEN_KEY_RE.test(k) && typeof v === 'string') {
        out[k] = redactToken(v)
      } else {
        out[k] = redactDeep(v)
      }
    }
    return out as T
  }
  if (typeof value === 'string' && TOKEN_VALUES_RE.test(value)) {
    // 单独长串不一定是 token，跳过（避免误伤）
  }
  return value
}

export function installDevConsoleGuard(): void {
  const wrap = (level: 'log' | 'debug' | 'info' | 'warn' | 'error') => {
    const original = console[level].bind(console) as (...args: unknown[]) => void
    console[level] = (...args: unknown[]) => {
      const redacted = args.map((a) => redactDeep(a))
      original(...redacted)
    }
  }
  wrap('log')
  wrap('debug')
  wrap('info')
  wrap('warn')
  wrap('error')
}

/**
 * 把常见错误码映射到 UI 反馈类型（info / warning / error）。
 * 见 web.md §二十二 错误处理与 traceId。
 * R1 阶段：返回类型保持不变（info/warning/error 分类），错误码映射逻辑不变。
 * R3 阶段：调用方从 console 改为 AppToast，分类语义保持兼容。
 */
export function errorToMessage(err: ApiError): { type: 'info' | 'warning' | 'error'; message: string } {
  const m = err.message || err.errorCode
  switch (err.errorCode) {
    // 业务 4xx
    case 'PROPOSAL_ALREADY_CONFIRMED':
    case 'PROPOSAL_NOT_FOUND':
    case 'PROPOSAL_PAYLOAD_MISMATCH':
    case 'CONVERSATION_ARCHIVED':
    case 'CONVERSATION_NOT_FOUND':
      return { type: 'warning', message: m }
    // 上传 400/409
    case 'CHUNK_HASH_MISMATCH':
    case 'CHUNK_SIZE_MISMATCH':
    case 'CHUNK_OUT_OF_RANGE':
    case 'INCOMPLETE_CHUNKS':
    case 'FILE_HASH_MISMATCH':
    case 'UPLOAD_TOO_LARGE':
    case 'PACKAGE_DEDUP_CONFLICT':
    case 'PACKAGE_ALREADY_REFERENCED':
    case 'UPLOAD_SESSION_NOT_FOUND':
    case 'UPLOAD_SESSION_NOT_UPLOADING':
      return { type: 'error', message: m }
    // 网络/通用
    case 'NETWORK_ERROR':
    case 'STREAM_INTERRUPTED':
      return { type: 'warning', message: m }
    case 'WS_RECONNECTING':
      return { type: 'info', message: `${m}（重连中...）` }
    // 5xx
    case 'INTERNAL_ERROR':
    case 'DEPENDENCY_UNAVAILABLE':
    default:
      return { type: 'error', message: m }
  }
}

/** 是否应当自动重试。 */
export function isRetryable(err: ApiError): boolean {
  if (err.status === 0) return true // network
  if (err.status === 429) return true
  if (err.status >= 500 && err.status < 600) return true
  return false
}

/** 错误码分类（用于 UI 表现）。 */
export type ErrorCategory =
  | 'network'
  | 'stream'
  | 'ws'
  | 'biz-4xx'
  | 'biz-410'
  | 'upload-400'
  | '5xx'
  | 'unknown'

export function categorize(err: ApiError): ErrorCategory {
  if (err.errorCode === 'WS_RECONNECTING') return 'ws'
  if (err.errorCode === 'STREAM_INTERRUPTED') return 'stream'
  if (err.status === 0 || err.errorCode === 'NETWORK_ERROR') return 'network'
  if (err.status === 410) return 'biz-410'
  if (
    err.errorCode?.startsWith('UPLOAD_') ||
    err.errorCode?.startsWith('CHUNK_') ||
    err.errorCode === 'FILE_HASH_MISMATCH' ||
    err.errorCode?.startsWith('PACKAGE_') ||
    err.errorCode === 'INCOMPLETE_CHUNKS'
  ) return 'upload-400'
  if (err.status >= 400 && err.status < 500) return 'biz-4xx'
  if (err.status >= 500 && err.status < 600) return '5xx'
  return 'unknown'
}
