export type AuthInvalidationReason = 'terminal-error' | 'logout'

type AuthInvalidationHandler = (reason: AuthInvalidationReason) => void

let invalidationHandler: AuthInvalidationHandler | null = null

export function setAuthInvalidationHandler(handler: AuthInvalidationHandler | null): void {
  invalidationHandler = handler
}

export function invalidateAuthSession(reason: AuthInvalidationReason): void {
  invalidationHandler?.(reason)
}
