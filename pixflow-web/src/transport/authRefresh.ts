type RefreshHandler = () => Promise<boolean>

let refreshHandler: RefreshHandler | null = null

export function setAuthRefreshHandler(handler: RefreshHandler | null): void {
  refreshHandler = handler
}

export async function refreshAuthSessionOnce(): Promise<boolean> {
  if (!refreshHandler) return false
  return await refreshHandler()
}
