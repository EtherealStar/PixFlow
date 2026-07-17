import { afterEach, describe, expect, it, vi } from 'vitest'
import { login, refresh } from '@/api/auth'
import { setAccessToken } from '@/transport/authToken'

function authResponse(): Response {
  return new Response(
    JSON.stringify({
      success: true,
      data: {
        accessToken: 'new-token',
        accessTokenExpiresAt: '2026-07-06T12:00:00Z',
        user: { username: 'alice' }
      }
    }),
    { status: 200, headers: { 'content-type': 'application/json' } }
  )
}

describe('auth api', () => {
  afterEach(() => {
    setAccessToken(null)
    vi.unstubAllGlobals()
  })

  it('does not send stale Authorization to login or refresh', async () => {
    const fetchMock = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) => authResponse())
    vi.stubGlobal('fetch', fetchMock)
    setAccessToken('stale-token')

    await login({ username: 'alice', password: 'password123' })
    await refresh()

    const calls = fetchMock.mock.calls.map((call) => {
      const path = call[0]
      const init = call[1] as RequestInit
      return { path, headers: init.headers as Record<string, string>, credentials: init.credentials }
    })

    expect(calls.map((call) => call.path)).toEqual(['/api/auth/login', '/api/auth/refresh'])
    expect(calls.every((call) => call.headers.Authorization === undefined)).toBe(true)
    expect(calls.every((call) => call.credentials === 'same-origin')).toBe(true)
  })
})
