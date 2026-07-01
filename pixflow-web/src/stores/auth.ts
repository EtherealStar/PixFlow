import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

/**
 * Auth store（web.md §十一 鉴权接入）
 *
 * 当前为最小 stub（仅用于布局/Header 编译通过）。
 * R6 阶段会接入 pixflow-infra-auth JWT 与 /api/auth 端点。
 *
 * 行为契约：
 * - 优先 httpOnly cookie（后端控制）
 * - 降级 localStorage.pixflow.auth.token
 * - HTTP 注入：Authorization: Bearer <jwt>
 */

const TOKEN_LS_KEY = 'pixflow.auth.token'

function readTokenFromLs(): string | null {
  try { return localStorage.getItem(TOKEN_LS_KEY) }
  catch { return null }
}

function writeTokenToLs(token: string | null): void {
  try {
    if (token) localStorage.setItem(TOKEN_LS_KEY, token)
    else localStorage.removeItem(TOKEN_LS_KEY)
  } catch { /* ignore */ }
}

export interface AuthUser {
  id: string
  name: string
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(readTokenFromLs())

  // stub user；R6 阶段接入 /api/auth/me 后实拉
  const user = ref<AuthUser | null>(token.value ? { id: 'demo', name: '演示账号' } : null)

  const isAuthenticated = computed(() => !!token.value && !!user.value)

  async function login(creds: { username: string; password: string }): Promise<void> {
    // R6 stub：直接接受任何非空账密
    if (!creds.username.trim() || !creds.password.trim()) {
      throw new Error('账号或密码不能为空')
    }
    const t = `stub.${Date.now()}.${Math.random().toString(36).slice(2)}`
    token.value = t
    user.value = { id: creds.username, name: creds.username }
    writeTokenToLs(t)
  }

  async function logout(): Promise<void> {
    token.value = null
    user.value = null
    writeTokenToLs(null)
  }

  function getToken(): string | null {
    return token.value
  }

  return { token, user, isAuthenticated, login, logout, getToken }
})