import { describe, expect, it } from 'vitest'
import { authErrorMessage } from '@/utils/authErrors'
import { ApiError } from '@/types/api'

describe('authErrorMessage', () => {
  it('maps known auth error code to Chinese message with trace id', () => {
    const error = new ApiError({
      status: 401,
      errorCode: 'AUTH_INVALID_CREDENTIALS',
      message: 'unauthorized',
      traceId: 'trace-1'
    })

    expect(authErrorMessage(error)).toBe('用户名或密码错误（TraceId: trace-1）')
  })

  it('falls back to backend message for unknown api error', () => {
    const error = new ApiError({
      status: 400,
      errorCode: 'AUTH_UNKNOWN',
      message: 'custom backend message',
      traceId: 'trace-2'
    })

    expect(authErrorMessage(error)).toBe('custom backend message（TraceId: trace-2）')
  })
})
