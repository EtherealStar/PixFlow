import { z } from 'zod'

/**
 * 统一错误响应（与 `base/common.md` envelope 对齐）。
 * HTTP 4xx/5xx body 解析为 ApiError，**不抛原生 Error**。
 */
export interface ApiError {
  status: number
  errorCode: string
  message: string
  traceId: string
  details?: Record<string, unknown>
  retryAfterMs?: number
}

/**
 * 幂等键。前端为 `/submit` 生成的 UUID，缓存在 sessionStorage。
 * 后端用来去重"用户重复点击 / 网络重试导致重复创建任务"。
 */
export interface IdempotencyKey {
  key: string
  proposalId: string
  savedAt: number
}

export interface TraceId {
  value: string
  source: 'request' | 'response' | 'sse-event' | 'ws-frame'
}

export interface Page<T> {
  items: T[]
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