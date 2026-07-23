import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '@/api/client'
import { getAccessToken, setAccessToken } from '@/transport/authToken'
import { getAuthSession } from '@/runtime/authSession'
import { ApiError } from '@/types/api'

function jsonResponse(data: unknown): Response {
  return new Response(JSON.stringify({ success: true, data }), {
    status: 200,
    headers: { 'content-type': 'application/json' }
  })
}

describe('request auth header', () => {
  beforeEach(() => {
    getAuthSession().clear()
  })

  afterEach(() => {
    getAuthSession().clear()
    vi.unstubAllGlobals()
  })

  it('attaches access token to authenticated requests', async () => {
    const fetchMock = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) => jsonResponse({ ok: true }))
    vi.stubGlobal('fetch', fetchMock)
    setAccessToken('good-token')

    await request('/api/protected', { noRetry: true })

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer good-token')
    expect(init.credentials).toBe('same-origin')
  })

  it('skips access token when auth is false', async () => {
    const fetchMock = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) => jsonResponse({ ok: true }))
    vi.stubGlobal('fetch', fetchMock)
    setAccessToken('bad-token')

    await request('/api/auth/login', { method: 'POST', body: { username: 'alice' }, auth: false, noRetry: true })

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect((init.headers as Record<string, string>).Authorization).toBeUndefined()
    expect(init.credentials).toBe('same-origin')
  })

  it('refreshes an expired access token once and retries the original request', async () => {
    setAccessToken('expired-token')
    const fetchMock = vi
      .fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        success: false,
        code: 'AUTH_TOKEN_EXPIRED',
        message: 'access token 已过期'
      }), { status: 401, headers: { 'content-type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        success: true,
        data: {
          accessToken: 'fresh-token',
          accessTokenExpiresAt: '2026-07-06T12:00:00Z',
          user: { userId: 1, username: 'alice', displayName: null }
        }
      }), { status: 200, headers: { 'content-type': 'application/json' } }))
      .mockResolvedValueOnce(jsonResponse({ ok: true }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(request('/api/protected', { noRetry: true })).resolves.toEqual({ ok: true })

    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([
      '/api/protected',
      '/api/auth/refresh',
      '/api/protected'
    ])
    expect((fetchMock.mock.calls[0]?.[1] as RequestInit).headers).toMatchObject({
      Authorization: 'Bearer expired-token'
    })
    expect((fetchMock.mock.calls[1]?.[1] as RequestInit).headers).not.toHaveProperty('Authorization')
    expect((fetchMock.mock.calls[2]?.[1] as RequestInit).headers).toMatchObject({
      Authorization: 'Bearer fresh-token'
    })
  })

  it('clears the session without refreshing when an access token is terminally invalid', async () => {
    setAccessToken('invalid-token')
    const invalidated = vi.fn()
    window.addEventListener('pixflow:auth-session-invalidated', invalidated, { once: true })
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      success: false,
      code: 'AUTH_TOKEN_INVALID',
      message: 'access token 无效'
    }), { status: 401, headers: { 'content-type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(request('/api/protected', { noRetry: true })).rejects.toMatchObject({
      status: 401,
      errorCode: 'AUTH_TOKEN_INVALID'
    })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(getAuthSession().phase.value).toBe('expired')
    expect(getAccessToken()).toBeNull()
    expect(invalidated).toHaveBeenCalledOnce()
  })

  it('throws an Error subclass while preserving a backend error envelope', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      success: false,
      code: 'INVALID_PARAM',
      message: '参数错误',
      details: { field: 'page' },
      traceId: 'server-trace'
    }), { status: 400, headers: { 'content-type': 'application/json' } })))

    const promise = request('/api/failure', { noRetry: true })

    await expect(promise).rejects.toBeInstanceOf(Error)
    await expect(promise).rejects.toBeInstanceOf(ApiError)
    await expect(promise).rejects.toMatchObject({
      status: 400,
      errorCode: 'INVALID_PARAM',
      message: '参数错误',
      details: { field: 'page' },
      traceId: 'server-trace'
    })
  })

  it('normalizes a fetch rejection to a structured network error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('connection lost')))

    await expect(request('/api/offline', { noRetry: true })).rejects.toMatchObject({
      name: 'ApiError',
      status: 0,
      errorCode: 'NETWORK_ERROR',
      message: 'connection lost'
    })
  })
})
