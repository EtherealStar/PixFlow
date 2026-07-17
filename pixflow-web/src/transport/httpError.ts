import { ApiError } from '@/types/api'

export function normalizeHttpError(
  status: number,
  body: unknown,
  fallbackTraceId: string
): ApiError {
  const obj = body && typeof body === 'object' ? (body as Record<string, unknown>) : undefined
  const errorCode = obj?.errorCode ?? obj?.code
  const message = obj?.message
  const traceId = obj?.traceId
  const details = obj?.details
  return new ApiError({
    status,
    errorCode: typeof errorCode === 'string' && errorCode ? errorCode : `HTTP_${status}`,
    message:
      typeof message === 'string' && message
        ? message
        : typeof body === 'string' && body.trim()
          ? body.trim()
          : `HTTP ${status}`,
    traceId: typeof traceId === 'string' && traceId ? traceId : fallbackTraceId,
    details: details && typeof details === 'object' && !Array.isArray(details)
      ? details as Record<string, unknown>
      : undefined
  })
}

export async function readHttpError(response: Response, fallbackTraceId: string): Promise<ApiError> {
  const responseTraceId = response.headers.get('X-Trace-Id') ?? fallbackTraceId
  let text = ''
  try {
    text = await response.text()
  } catch {
    // 响应体不可读时仍保留 HTTP 状态和 traceId。
  }
  let body: unknown = text
  if (text) {
    try {
      body = JSON.parse(text)
    } catch {
      // 代理返回 HTML/纯文本时由 normalizeHttpError 走 HTTP_<status> 兜底。
    }
  }
  return normalizeHttpError(response.status, body, responseTraceId)
}
