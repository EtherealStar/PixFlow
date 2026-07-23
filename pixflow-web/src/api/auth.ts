import { request } from './client'
import { z } from 'zod'

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

const authUserSchema = z.strictObject({
  userId: z.number().int().positive(),
  username: z.string().min(1),
  displayName: z.string().nullable().optional()
})

const authTokenPayloadSchema = z.strictObject({
  accessToken: z.string().min(1),
  accessTokenExpiresAt: z.string().min(1),
  user: authUserSchema
})

export async function login(req: LoginRequest): Promise<AuthTokenPayload> {
  return authTokenPayloadSchema.parse(await request<unknown>('/api/auth/login', { method: 'POST', body: req, noRetry: true, auth: false }))
}

export async function refresh(): Promise<AuthTokenPayload> {
  return authTokenPayloadSchema.parse(await request<unknown>('/api/auth/refresh', { method: 'POST', noRetry: true, auth: false }))
}

export async function me(): Promise<AuthUser> {
  return authUserSchema.parse(await request<unknown>('/api/auth/me', { noRetry: true }))
}

export function logout(): Promise<void> {
  return request<void>('/api/auth/logout', {
    method: 'POST',
    noRetry: true,
    authRefresh: false,
    authInvalidation: false
  })
}
