import type { ApiError } from '@/types/api'

const AUTH_ERROR_MESSAGES: Record<string, string> = {
  AUTH_USERNAME_TAKEN: '用户名已被占用',
  AUTH_INVALID_CREDENTIALS: '用户名或密码错误',
  AUTH_USERNAME_INVALID: '用户名只能包含小写字母、数字和下划线，长度 3-32 位',
  AUTH_PASSWORD_INVALID: '密码长度需为 8-128 位',
  AUTH_TOO_MANY_ATTEMPTS: '尝试次数过多，请稍后再试',
  AUTH_ACCOUNT_DISABLED: '账号已被禁用',
  AUTH_REFRESH_EXPIRED: '登录状态已过期，请重新登录',
  AUTH_REFRESH_INVALID: '登录状态无效，请重新登录',
  AUTH_TOKEN_INVALID: '登录状态无效，请重新登录',
  AUTH_TOKEN_EXPIRED: '登录状态已过期，请重新登录',
  AUTH_TOKEN_REVOKED: '登录状态已失效，请重新登录'
}

function isApiError(error: unknown): error is ApiError {
  return !!error && typeof error === 'object' && 'errorCode' in error && 'status' in error
}

export function authErrorMessage(error: unknown): string {
  if (isApiError(error)) {
    const message = AUTH_ERROR_MESSAGES[error.errorCode] ?? error.message
    return error.traceId ? `${message}（TraceId: ${error.traceId}）` : message
  }
  if (error instanceof Error && error.message) return error.message
  return '请求失败，请稍后重试'
}
