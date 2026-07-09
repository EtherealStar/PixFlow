import { request } from './client'

export interface AuthUser {
  userId: number
  username: string
  displayName?: string | null
  status?: string
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

export interface RegisterRequest {
  username: string
  password: string
  displayName?: string
}

export function login(req: LoginRequest): Promise<AuthTokenPayload> {
  return request<AuthTokenPayload>('/api/auth/login', { method: 'POST', body: req, noRetry: true, auth: false })
}

export function register(req: RegisterRequest): Promise<AuthTokenPayload> {
  return request<AuthTokenPayload>('/api/auth/register', { method: 'POST', body: req, noRetry: true, auth: false })
}

export function refresh(): Promise<AuthTokenPayload> {
  return request<AuthTokenPayload>('/api/auth/refresh', { method: 'POST', noRetry: true, auth: false })
}

export function me(): Promise<AuthUser> {
  return request<AuthUser>('/api/auth/me', { noRetry: true })
}

export function logout(): Promise<void> {
  return request<void>('/api/auth/logout', { method: 'POST', noRetry: true })
}
