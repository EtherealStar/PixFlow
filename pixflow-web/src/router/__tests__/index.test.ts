import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { router } from '@/router'
import { getAuthSession } from '@/runtime/authSession'
import * as authApi from '@/api/auth'

vi.mock('@/api/auth', () => ({
  me: vi.fn(() => {
    throw new Error('not authenticated')
  }),
  refresh: vi.fn(() => {
    throw new Error('no refresh session')
  }),
  login: vi.fn(),
  logout: vi.fn()
}))

describe('router auth guard', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    getAuthSession().clear()
    await router.replace('/')
    await router.isReady()
  })

  it('redirects unauthenticated protected routes to login page', async () => {
    await router.push('/chat/new')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query).toMatchObject({
      redirect: '/chat/new'
    })
    expect(authApi.refresh).not.toHaveBeenCalled()
    expect(authApi.me).not.toHaveBeenCalled()
  })

  it('does not refresh auth state when visiting the login page', async () => {
    getAuthSession().clear()

    await router.push('/login')

    expect(router.currentRoute.value.name).toBe('login')
    expect(authApi.refresh).not.toHaveBeenCalled()
    expect(authApi.me).not.toHaveBeenCalled()
  })
})
