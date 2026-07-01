/**
 * IdempotencyKey 缓存层（sessionStorage）。
 * 见 web.md §十四「Idempotency-Key 介质」：key = `pixflow.idemp.{proposalId}`，TTL 30min。
 * 多标签页共享同一 proposalId 时复用同一 key。
 */
const PREFIX = 'pixflow.idemp.'
const TTL_MS = 30 * 60 * 1000

function keyFor(proposalId: string): string {
  return `${PREFIX}${proposalId}`
}

export function getIdempotencyKey(proposalId: string): string | null {
  try {
    const raw = sessionStorage.getItem(keyFor(proposalId))
    if (!raw) return null
    const parsed = JSON.parse(raw) as { key: string; savedAt: number }
    if (Date.now() - parsed.savedAt > TTL_MS) {
      sessionStorage.removeItem(keyFor(proposalId))
      return null
    }
    return parsed.key
  } catch {
    return null
  }
}

export function setIdempotencyKey(proposalId: string, key: string): void {
  try {
    sessionStorage.setItem(
      keyFor(proposalId),
      JSON.stringify({ key, savedAt: Date.now() })
    )
  } catch {
    // sessionStorage 写入失败（隐私模式 / 配额），忽略
  }
}

export function clearIdempotencyKey(proposalId: string): void {
  try {
    sessionStorage.removeItem(keyFor(proposalId))
  } catch {
    // 忽略
  }
}