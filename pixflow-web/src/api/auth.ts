import { request } from './client'

export interface AuthUser {
  userId: number
  username: string
  displayName?: string | null
}

export interface AuthTokenPayload {
  accessToken: string
  accessTokenExpiresAt: string
  user: AuthUser
}

export interface LoginRequest {
  username: string
  password: string
}

export function login(req: LoginRequest): Promise<AuthTokenPayload> {
  return request<AuthTokenPayload>('/api/auth/login', { method: 'POST', body: req, noRetry: true, auth: false })
}

export function refresh(): Promise<AuthTokenPayload> {
  return request<AuthTokenPayload>('/api/auth/refresh', { method: 'POST', noRetry: true, auth: false })
}

export function me(): Promise<AuthUser> {
  return request<AuthUser>('/api/auth/me', { noRetry: true })
}

export function logout(): Promise<void> {
  return request<void>('/api/auth/logout', {
    method: 'POST',
    noRetry: true,
    authRefresh: false,
    authInvalidation: false
  })
}
