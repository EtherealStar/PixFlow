import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { getAccessToken, setAccessToken } from '@/transport/authToken'
import * as authApi from '@/api/auth'

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  refresh: vi.fn(),
  me: vi.fn(),
  logout: vi.fn()
}))

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    setAccessToken(null)
    vi.mocked(authApi.login).mockReset()
    vi.mocked(authApi.logout).mockReset()
  })

  it('stores token and user after login succeeds', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      accessToken: 'login-token',
      accessTokenExpiresAt: '2026-07-06T12:00:00Z',
      user: { userId: 42, username: 'alice', displayName: 'Alice' }
    })

    const auth = useAuthStore()
    await auth.login({ username: 'alice', password: 'password123' })

    expect(auth.isAuthenticated).toBe(true)
    expect(auth.user).toEqual({ userId: 42, username: 'alice', displayName: 'Alice' })
    expect(getAccessToken()).toBe('login-token')
  })

  it('clears memory state and announces navigation after logout', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      accessToken: 'login-token',
      accessTokenExpiresAt: '2026-07-06T12:00:00Z',
      user: { userId: 42, username: 'alice', displayName: 'Alice' }
    })
    vi.mocked(authApi.logout).mockResolvedValue()
    const invalidated = vi.fn()
    window.addEventListener('pixflow:auth-session-invalidated', invalidated, { once: true })
    const auth = useAuthStore()
    await auth.login({ username: 'alice', password: 'password123' })

    await auth.logout()

    expect(auth.isAuthenticated).toBe(false)
    expect(auth.phase).toBe('anonymous')
    expect(getAccessToken()).toBeNull()
    expect(invalidated).toHaveBeenCalledOnce()
  })
})
