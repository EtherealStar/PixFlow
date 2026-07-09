const TOKEN_LS_KEY = 'pixflow.auth.token'

let accessToken: string | null = readStoredToken()

export function getAccessToken(): string | null {
  return accessToken
}

export function setAccessToken(token: string | null): void {
  accessToken = token
  try {
    if (token) localStorage.setItem(TOKEN_LS_KEY, token)
    else localStorage.removeItem(TOKEN_LS_KEY)
  } catch {
    // localStorage 不可用时仅保留内存态。
  }
}

export function readStoredToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_LS_KEY)
  } catch {
    return null
  }
}
