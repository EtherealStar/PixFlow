import { ref, computed, type ComputedRef, type Ref } from 'vue'
import * as authApi from '@/api/auth'
import { getAccessToken, setAccessToken } from '@/transport/authToken'
import { setAuthRefreshHandler } from '@/transport/authRefresh'
import {
  invalidateAuthSession,
  setAuthInvalidationHandler,
  type AuthInvalidationReason
} from '@/transport/authInvalidation'
import { disposeStompConnection } from '@/transport/ws'

export type AuthSessionPhase = 'anonymous' | 'bootstrapping' | 'authenticated' | 'expired'

export const AUTH_SESSION_INVALIDATED_EVENT = 'pixflow:auth-session-invalidated'

export interface AuthUser {
  userId: number
  username: string
  displayName?: string | null
}

export interface AuthSessionState {
  phase: Ref<AuthSessionPhase>
  token: Ref<string | null>
  user: Ref<AuthUser | null>
  bootstrapping: Ref<boolean>
  isAuthenticated: ComputedRef<boolean>
  bootstrap: () => Promise<boolean>
  login: (creds: { username: string; password: string }) => Promise<void>
  refreshOnce: () => Promise<boolean>
  logout: () => Promise<void>
  clear: () => void
  getToken: () => string | null
  hasBootstrapped: () => boolean
}

const phase = ref<AuthSessionPhase>('anonymous')
const token = ref<string | null>(getAccessToken())
const user = ref<AuthUser | null>(null)
const bootstrapping = ref(false)
const isAuthenticated = computed(() => phase.value === 'authenticated' && !!token.value && !!user.value)

let bootstrapPromise: Promise<boolean> | null = null
let refreshPromise: Promise<boolean> | null = null
let sessionGeneration = 0
let bootstrapStarted = false

function nextGeneration(): number {
  sessionGeneration += 1
  return sessionGeneration
}

function applyToken(payload: authApi.AuthTokenPayload): void {
  nextGeneration()
  setAccessToken(payload.accessToken)
  token.value = payload.accessToken
  user.value = toUser(payload.user)
  phase.value = 'authenticated'
}

function clearLocal(nextPhase: AuthSessionPhase = 'anonymous'): void {
  nextGeneration()
  bootstrapStarted = true
  disposeStompConnection()
  setAccessToken(null)
  token.value = null
  user.value = null
  phase.value = nextPhase
}

async function bootstrap(): Promise<boolean> {
  if (isAuthenticated.value) return true
  if (bootstrapPromise) return bootstrapPromise
  bootstrapStarted = true
  const generation = sessionGeneration
  bootstrapping.value = true
  phase.value = 'bootstrapping'
  bootstrapPromise = bootstrapInternal(generation).finally(() => {
    bootstrapPromise = null
    bootstrapping.value = false
  })
  return bootstrapPromise
}

async function bootstrapInternal(generation: number): Promise<boolean> {
  const existingToken = getAccessToken()
  if (existingToken) {
    token.value = existingToken
    try {
      const current = await authApi.me()
      if (generation !== sessionGeneration) return isAuthenticated.value
      user.value = toUser(current)
      phase.value = 'authenticated'
      return true
    } catch {
      if (generation !== sessionGeneration) return isAuthenticated.value
      setAccessToken(null)
      token.value = null
      user.value = null
    }
  }
  try {
    const payload = await authApi.refresh()
    if (generation !== sessionGeneration) return isAuthenticated.value
    setAccessToken(payload.accessToken)
    token.value = payload.accessToken
    user.value = toUser(payload.user)
    phase.value = 'authenticated'
    return true
  } catch {
    if (generation !== sessionGeneration) return isAuthenticated.value
    setAccessToken(null)
    token.value = null
    user.value = null
    phase.value = 'anonymous'
    return false
  }
}

async function login(creds: { username: string; password: string }): Promise<void> {
  if (!creds.username.trim() || !creds.password.trim()) {
    throw new Error('账号或密码不能为空')
  }
  const payload = await authApi.login(creds)
  applyToken(payload)
}

async function refreshOnce(): Promise<boolean> {
  if (refreshPromise) return refreshPromise
  const generation = sessionGeneration
  refreshPromise = authApi.refresh()
    .then((payload) => {
      if (generation !== sessionGeneration) return isAuthenticated.value
      setAccessToken(payload.accessToken)
      token.value = payload.accessToken
      user.value = toUser(payload.user)
      phase.value = 'authenticated'
      return true
    })
    .catch(() => {
      if (generation === sessionGeneration) invalidateAuthSession('terminal-error')
      return false
    })
    .finally(() => {
      refreshPromise = null
    })
  return refreshPromise
}

async function logout(): Promise<void> {
  try {
    await authApi.logout()
  } finally {
    invalidateAuthSession('logout')
  }
}

function clear(): void {
  clearLocal('anonymous')
}

function getToken(): string | null {
  return token.value
}

function hasBootstrapped(): boolean {
  return bootstrapStarted
}

export function getAuthSession(): AuthSessionState {
  return {
    phase,
    token,
    user,
    bootstrapping,
    isAuthenticated,
    bootstrap,
    login,
    refreshOnce,
    logout,
    clear,
    getToken,
    hasBootstrapped
  }
}

function toUser(u: authApi.AuthUser): AuthUser {
  return {
    username: u.username,
    userId: u.userId,
    displayName: u.displayName ?? null
  }
}

setAuthRefreshHandler(refreshOnce)
setAuthInvalidationHandler((reason: AuthInvalidationReason) => {
  clearLocal(reason === 'logout' ? 'anonymous' : 'expired')
  window.dispatchEvent(new CustomEvent(AUTH_SESSION_INVALIDATED_EVENT, { detail: { reason } }))
})
