import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '@/api/client'
import { setAccessToken } from '@/transport/authToken'
import { getAuthSession } from '@/runtime/authSession'

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
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => jsonResponse({ ok: true }))
    vi.stubGlobal('fetch', fetchMock)
    setAccessToken('good-token')

    await request('/api/protected', { noRetry: true })

    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer good-token')
    expect(init.credentials).toBe('same-origin')
  })

  it('skips access token when auth is false', async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => jsonResponse({ ok: true }))
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
      .fn()
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
          user: { username: 'alice' }
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
})
