import { defineStore } from 'pinia'
import { getAuthSession, type AuthUser } from '@/runtime/authSession'

/**
 * Auth store（web.md §十一 鉴权接入）
 *
 * 行为契约：
 * - 认证状态机由 runtime/authSession.ts 统一拥有
 * - store 只暴露页面和路由需要的响应式状态
 * - 路由守卫不直接触发 refresh，避免登录页和跳转时重复打 /api/auth/refresh
 */
export type { AuthUser }

export const useAuthStore = defineStore('auth', () => {
  const session = getAuthSession()

  async function login(creds: { username: string; password: string }): Promise<void> {
    await session.login(creds)
  }

  async function register(creds: { username: string; password: string; displayName?: string }): Promise<void> {
    await session.register(creds)
  }

  async function bootstrap(): Promise<boolean> {
    return await session.bootstrap()
  }

  async function restore(): Promise<boolean> {
    return await bootstrap()
  }

  async function logout(): Promise<void> {
    await session.logout()
  }

  function clear(): void {
    session.clear()
  }

  function getToken(): string | null {
    return session.getToken()
  }

  function hasBootstrapped(): boolean {
    return session.hasBootstrapped()
  }

  return {
    token: session.token,
    user: session.user,
    phase: session.phase,
    restoring: session.bootstrapping,
    bootstrapping: session.bootstrapping,
    isAuthenticated: session.isAuthenticated,
    login,
    register,
    bootstrap,
    restore,
    logout,
    clear,
    getToken,
    hasBootstrapped
  }
})
