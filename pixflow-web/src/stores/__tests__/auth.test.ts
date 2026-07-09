import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { getAccessToken, setAccessToken } from '@/transport/authToken'
import * as authApi from '@/api/auth'

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  register: vi.fn(),
  refresh: vi.fn(),
  me: vi.fn(),
  logout: vi.fn()
}))

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    setAccessToken(null)
    vi.mocked(authApi.register).mockReset()
  })

  it('stores token and user after register succeeds', async () => {
    vi.mocked(authApi.register).mockResolvedValue({
      accessToken: 'registered-token',
      accessTokenExpiresAt: '2026-07-06T12:00:00Z',
      user: { userId: 42, username: 'alice', displayName: 'Alice' }
    })

    const auth = useAuthStore()
    await auth.register({ username: 'alice', password: 'password123' })

    expect(auth.isAuthenticated).toBe(true)
    expect(auth.user).toEqual({ userId: 42, username: 'alice', displayName: 'Alice', status: undefined })
    expect(getAccessToken()).toBe('registered-token')
  })
})
