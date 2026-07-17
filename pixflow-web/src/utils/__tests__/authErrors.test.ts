import { describe, expect, it } from 'vitest'
import { authErrorMessage } from '@/utils/authErrors'
import { ApiError } from '@/types/api'

describe('authErrorMessage', () => {
  it('maps known auth error code to Chinese message with trace id', () => {
    const error = new ApiError({
      status: 409,
      errorCode: 'AUTH_USERNAME_TAKEN',
      message: 'conflict',
      traceId: 'trace-1'
    })

    expect(authErrorMessage(error)).toBe('用户名已被占用（TraceId: trace-1）')
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
