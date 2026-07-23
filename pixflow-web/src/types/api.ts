import { z } from 'zod'

/**
 * 统一错误响应（与 `base/common.md` envelope 对齐）。
 * HTTP 4xx/5xx body 解析为携带业务字段的 Error 子类。
 */
export interface ApiErrorInit {
  status: number
  errorCode: string
  message: string
  traceId: string
  details?: Record<string, unknown>
  retryAfterMs?: number
}

export class ApiError extends Error {
  readonly status: number
  readonly errorCode: string
  readonly traceId: string
  readonly details?: Record<string, unknown>
  retryAfterMs?: number

  constructor(init: ApiErrorInit, options: { cause?: unknown } = {}) {
    super(init.message, options.cause === undefined ? undefined : { cause: options.cause })
    Object.setPrototypeOf(this, new.target.prototype)
    this.name = 'ApiError'
    this.status = init.status
    this.errorCode = init.errorCode
    this.traceId = init.traceId
    this.details = init.details
    this.retryAfterMs = init.retryAfterMs
  }

  static fromUnknown(error: unknown, fallback: ApiErrorInit): ApiError {
    if (error instanceof ApiError) return error
    const record = isRecord(error) ? error : undefined
    const details = isRecord(record?.details) ? record.details : fallback.details
    return new ApiError({
      status: typeof record?.status === 'number' ? record.status : fallback.status,
      errorCode: stringValue(record?.errorCode) ?? stringValue(record?.code) ?? fallback.errorCode,
      message: error instanceof Error ? error.message : stringValue(record?.message) ?? fallback.message,
      traceId: stringValue(record?.traceId) ?? fallback.traceId,
      details,
      retryAfterMs: typeof record?.retryAfterMs === 'number' ? record.retryAfterMs : fallback.retryAfterMs
    }, { cause: error })
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

export interface TraceId {
  value: string
  source: 'request' | 'response' | 'sse-event' | 'ws-frame'
}

export interface Page<T> {
  records: T[]
  total: number
  page: number
  size: number
}

/** 后端 ApiResponse envelope 解析（成功/失败同构）。 */
export const apiResponseSchema = <T extends z.ZodTypeAny>(data: T) =>
  z.object({
    success: z.boolean(),
    code: z.string(),
    message: z.string().nullish(),
    data: data.nullish(),
    details: z.record(z.string(), z.unknown()).nullish(),
    traceId: z.string().nullish()
  })

export const errorPayloadSchema = z.object({
  code: z.string().optional(),
  errorCode: z.string().optional(),
  message: z.string().optional(),
  details: z.record(z.string(), z.unknown()).optional(),
  traceId: z.string().optional()
})
